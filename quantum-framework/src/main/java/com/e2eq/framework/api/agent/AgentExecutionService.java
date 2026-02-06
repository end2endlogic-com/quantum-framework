package com.e2eq.framework.api.agent;

import com.e2eq.framework.api.agent.llm.LlmChatResponse;
import com.e2eq.framework.api.agent.llm.LlmMessage;
import com.e2eq.framework.api.agent.llm.LlmToolCall;
import com.e2eq.framework.api.agent.llm.LLMClient;
import com.e2eq.framework.api.agent.llm.ProviderToolSchema;
import com.e2eq.framework.api.agent.llm.ToolFormatConverter;
import com.e2eq.framework.api.tools.ExecutionContext;
import com.e2eq.framework.api.tools.ToolResult;
import com.e2eq.framework.api.tools.ToolExecutor;
import com.e2eq.framework.model.persistent.agent.Agent;
import com.e2eq.framework.model.persistent.agent.AgentConversation;
import com.e2eq.framework.model.persistent.agent.AgentConversationTurn;
import com.e2eq.framework.model.persistent.agent.PromptStep;
import com.e2eq.framework.model.persistent.agent.ToolInvocationRecord;
import com.e2eq.framework.model.persistent.morphia.AgentConversationRepo;
import com.e2eq.framework.model.persistent.morphia.AgentConversationTurnRepo;
import com.e2eq.framework.model.persistent.morphia.AgentRepo;
import com.e2eq.framework.model.persistent.morphia.ToolDefinitionRepo;
import com.e2eq.framework.model.persistent.tools.ToolDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Executes an agent: observe (context + history) → think (LLM) → act (tools) → loop.
 * Persists conversations and turns; uses {@link GuardrailEvaluator} before tool execution.
 */
@ApplicationScoped
public class AgentExecutionService {

    private static final int MAX_ITERATIONS = 10;

    @Inject
    AgentRepo agentRepo;
    @Inject
    ToolResolver toolResolver;
    @Inject
    ToolDefinitionRepo toolDefinitionRepo;
    @Inject
    LLMClient llmClient;
    @Inject
    ToolFormatConverter toolFormatConverter;
    @Inject
    GuardrailEvaluator guardrailEvaluator;
    @Inject
    ToolExecutor toolExecutor;
    @Inject
    AgentConversationRepo conversationRepo;
    @Inject
    AgentConversationTurnRepo turnRepo;

    /**
     * Executes an agent: new or existing conversation, one observe-think-act loop (up to max iterations).
     *
     * @param request agentRef, userMessage, optional conversationId, realmId, principalId
     * @return result with conversationId, responseText, toolCalls, status
     */
    public AgentExecutionResult execute(AgentExecutionRequest request) {
        AgentExecutionResult result = new AgentExecutionResult();
        if (request == null || request.getAgentRef() == null || request.getAgentRef().isBlank()) {
            result.setStatus("ERROR");
            return result;
        }
        String realm = request.getRealmId() != null && !request.getRealmId().isBlank()
            ? request.getRealmId()
            : null;
        String principalId = request.getPrincipalId();

        Optional<Agent> optAgent = request.getRealmId() != null
            ? agentRepo.findByRefName(request.getRealmId(), request.getAgentRef())
            : Optional.empty();
        if (optAgent.isEmpty()) {
            result.setStatus("ERROR");
            return result;
        }
        Agent agent = optAgent.get();
        if (realm == null) {
            realm = request.getRealmId();
        }

        List<ToolDefinition> tools = toolResolver.resolveToolsForAgent(agent, realm);
        List<ProviderToolSchema> toolSchemas = toolFormatConverter.toProviderSchemas(tools);
        Map<String, ToolDefinition> toolsByRef = tools.stream()
            .filter(t -> t.getRefName() != null)
            .collect(Collectors.toMap(ToolDefinition::getRefName, t -> t, (a, b) -> a));

        AgentConversation conversation;
        if (request.getConversationId() != null && !request.getConversationId().isBlank()) {
            Optional<AgentConversation> optConv = conversationRepo.findById(realm, request.getConversationId());
            if (optConv.isEmpty()) {
                result.setStatus("ERROR");
                return result;
            }
            conversation = optConv.get();
        } else {
            conversation = new AgentConversation();
            conversation.setAgentRef(agent.getRefName());
            conversation.setPrincipalId(principalId != null ? principalId : "anonymous");
            conversation.setTitle(request.getUserMessage() != null && request.getUserMessage().length() > 50
                ? request.getUserMessage().substring(0, 50) + "..."
                : request.getUserMessage());
            conversation.setStatus("ACTIVE");
            conversation.setTurnCount(0);
            conversation = conversationRepo.save(realm, conversation);
        }

        List<AgentConversationTurn> history = turnRepo.listByConversationId(realm, conversation.getId().toHexString());
        List<LlmMessage> messages = buildMessages(agent, history, request.getUserMessage());

        ExecutionContext ctx = new ExecutionContext();
        ctx.setRealmId(realm);
        ctx.setPrincipalId(principalId);
        ctx.setAgentRef(agent.getRefName());

        int iterations = 0;
        int nextTurnIndex = history.size();
        String responseText = null;
        List<AgentExecutionResult.ToolInvocationResult> allToolCalls = new ArrayList<>();
        int totalTokens = 0;

        while (iterations < MAX_ITERATIONS) {
            iterations++;
            LlmChatResponse llmResponse = llmClient.chat(messages, toolSchemas);
            if (llmResponse.getInputTokens() != null) totalTokens += llmResponse.getInputTokens();
            if (llmResponse.getOutputTokens() != null) totalTokens += llmResponse.getOutputTokens();

            if (llmResponse.getText() != null && !llmResponse.getText().isBlank()) {
                responseText = llmResponse.getText();
                saveTurn(realm, conversation.getId().toHexString(), nextTurnIndex++, "assistant", responseText, null);
                conversation.setTurnCount(conversation.getTurnCount() + 1);
                conversationRepo.save(realm, conversation);
                result.setConversationId(conversation.getId().toHexString());
                result.setResponseText(responseText);
                result.setToolCalls(allToolCalls);
                result.setIterationsUsed(iterations);
                result.setTokensUsed(totalTokens);
                result.setStatus("COMPLETED");
                return result;
            }

            if (llmResponse.getToolCalls() == null || llmResponse.getToolCalls().isEmpty()) {
                result.setConversationId(conversation.getId().toHexString());
                result.setResponseText(responseText != null ? responseText : "");
                result.setToolCalls(allToolCalls);
                result.setIterationsUsed(iterations);
                result.setTokensUsed(totalTokens);
                result.setStatus("COMPLETED");
                return result;
            }

            saveTurn(realm, conversation.getId().toHexString(), nextTurnIndex++, "assistant", null, llmResponse.getToolCalls());
            List<ToolInvocationRecord> invocationRecords = new ArrayList<>();
            StringBuilder toolContentBuilder = new StringBuilder();

            for (LlmToolCall tc : llmResponse.getToolCalls()) {
                String toolRef = tc.getName();
                Map<String, Object> args = tc.getArguments() != null ? tc.getArguments() : Map.of();
                ToolDefinition toolDef = toolsByRef.get(toolRef);

                GuardrailResult guard = guardrailEvaluator.evaluate(agent, toolDef, args);
                if (guard == GuardrailResult.DENY) {
                    AgentExecutionResult.ToolInvocationResult tr = new AgentExecutionResult.ToolInvocationResult();
                    tr.setToolRef(toolRef);
                    tr.setInput(args);
                    tr.setStatus("SKIPPED");
                    tr.setErrorMessage("Guardrail denied");
                    allToolCalls.add(tr);
                    continue;
                }
                if (guard == GuardrailResult.REQUIRE_CONFIRMATION) {
                    result.setConversationId(conversation.getId().toHexString());
                    result.setResponseText("");
                    result.setToolCalls(allToolCalls);
                    result.setIterationsUsed(iterations);
                    result.setTokensUsed(totalTokens);
                    result.setStatus("AWAITING_APPROVAL");
                    return result;
                }

                long start = System.currentTimeMillis();
                ToolResult execResult = toolExecutor.execute(toolRef, args, ctx);
                long durationMs = System.currentTimeMillis() - start;

                ToolInvocationRecord rec = new ToolInvocationRecord();
                rec.setToolRef(toolRef);
                rec.setInput(args);
                rec.setOutput(execResult.getData());
                rec.setDurationMs(durationMs);
                rec.setStatus(execResult.getStatus() != null ? execResult.getStatus().name() : "UNKNOWN");
                rec.setErrorMessage(execResult.getErrorMessage());
                invocationRecords.add(rec);

                AgentExecutionResult.ToolInvocationResult tr = new AgentExecutionResult.ToolInvocationResult();
                tr.setToolRef(toolRef);
                tr.setInput(args);
                tr.setOutput(execResult.getData());
                tr.setDurationMs(durationMs);
                tr.setStatus(rec.getStatus());
                tr.setErrorMessage(execResult.getErrorMessage());
                allToolCalls.add(tr);

                Map<String, Object> toolResultForLlm = new LinkedHashMap<>();
                toolResultForLlm.put("tool_use_id", tc.getId());
                toolResultForLlm.put("content", execResult.getData() != null ? execResult.getData() : Map.of("error", execResult.getErrorMessage()));
                toolContentBuilder.append(toolResultForLlm.toString()).append("\n");
            }

            messages.add(new LlmMessage("assistant", "[tool calls]"));
            messages.add(new LlmMessage("tool", toolContentBuilder.toString()));
            saveTurnToolResults(realm, conversation.getId().toHexString(), nextTurnIndex++, invocationRecords);
        }

        result.setConversationId(conversation.getId().toHexString());
        result.setResponseText(responseText != null ? responseText : "");
        result.setToolCalls(allToolCalls);
        result.setIterationsUsed(iterations);
        result.setTokensUsed(totalTokens);
        result.setStatus("MAX_ITERATIONS");
        return result;
    }

    /**
     * Continues an existing conversation with a new user message.
     */
    public AgentExecutionResult continueConversation(String realmId, String conversationId, String userMessage, String principalId) {
        AgentExecutionRequest req = new AgentExecutionRequest();
        req.setRealmId(realmId);
        req.setConversationId(conversationId);
        req.setUserMessage(userMessage);
        req.setPrincipalId(principalId);
        Optional<AgentConversation> opt = conversationId != null && realmId != null
            ? conversationRepo.findById(realmId, conversationId)
            : Optional.empty();
        if (opt.isEmpty()) {
            AgentExecutionResult r = new AgentExecutionResult();
            r.setStatus("ERROR");
            return r;
        }
        req.setAgentRef(opt.get().getAgentRef());
        return execute(req);
    }

    private List<LlmMessage> buildMessages(Agent agent, List<AgentConversationTurn> history, String userMessage) {
        List<LlmMessage> messages = new ArrayList<>();
        String systemPrompt = buildSystemPrompt(agent);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new LlmMessage("system", systemPrompt));
        }
        for (AgentConversationTurn t : history) {
            if (t.getRole() != null && t.getContent() != null) {
                messages.add(new LlmMessage(t.getRole(), t.getContent()));
            }
        }
        if (userMessage != null && !userMessage.isBlank()) {
            messages.add(new LlmMessage("user", userMessage));
        }
        return messages;
    }

    private String buildSystemPrompt(Agent agent) {
        if (agent.getContext() == null || agent.getContext().isEmpty()) {
            return "";
        }
        return agent.getContext().stream()
            .sorted((a, b) -> Integer.compare(a.getOrder(), b.getOrder()))
            .map(PromptStep::getContent)
            .filter(c -> c != null && !c.isBlank())
            .collect(Collectors.joining("\n\n"));
    }

    private void saveTurn(String realm, String conversationId, int turnIndex, String role, String content, List<LlmToolCall> toolCalls) {
        AgentConversationTurn turn = new AgentConversationTurn();
        turn.setConversationId(conversationId);
        turn.setTurnIndex(turnIndex);
        turn.setRole(role);
        turn.setContent(content);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            List<ToolInvocationRecord> recs = new ArrayList<>();
            for (LlmToolCall tc : toolCalls) {
                ToolInvocationRecord r = new ToolInvocationRecord();
                r.setToolRef(tc.getName());
                r.setInput(tc.getArguments());
                r.setStatus("PENDING");
                recs.add(r);
            }
            turn.setToolCalls(recs);
        }
        turnRepo.save(realm, turn);
    }

    private void saveTurnToolResults(String realm, String conversationId, int turnIndex, List<ToolInvocationRecord> invocationRecords) {
        AgentConversationTurn turn = new AgentConversationTurn();
        turn.setConversationId(conversationId);
        turn.setTurnIndex(turnIndex);
        turn.setRole("tool");
        turn.setContent("[tool results]");
        turn.setToolCalls(invocationRecords);
        turnRepo.save(realm, turn);
    }
}
