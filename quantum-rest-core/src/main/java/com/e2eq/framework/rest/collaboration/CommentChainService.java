package com.e2eq.framework.rest.collaboration;

import com.e2eq.framework.model.persistent.base.ActorReference;
import com.e2eq.framework.model.persistent.collaboration.Comment;
import com.e2eq.framework.model.persistent.collaboration.CommentChain;
import com.e2eq.framework.model.persistent.morphia.CommentChainRepo;
import com.e2eq.framework.model.persistent.morphia.CommentRepo;
import com.e2eq.framework.rest.models.CommentChainCreateRequest;
import com.e2eq.framework.rest.models.CommentCreateRequest;
import com.mongodb.MongoWriteException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.bson.types.ObjectId;

@ApplicationScoped
public class CommentChainService {

    @Inject
    CommentChainRepo chainRepo;

    @Inject
    CommentRepo commentRepo;

    public CommentChain createChain(CommentChainCreateRequest request, ActorReference actor) {
        requireActor(actor);
        if (request == null || request.subject == null || !request.subject.isTargetValid()) {
            throw new CommentOperationException(
                    CommentOperationException.Code.INVALID_REQUEST,
                    "Exactly one local or external comment subject is required");
        }
        Instant now = Instant.now();
        String refName = "comment-chain-" + UUID.randomUUID();
        String displayName = clean(request.displayName);
        if (displayName == null) {
            displayName = "Comment chain " + refName.substring(refName.length() - 8);
        }
        CommentChain chain = CommentChain.builder()
                .refName(refName)
                .displayName(displayName)
                .subject(request.subject)
                .createdBy(actor)
                .createdAt(now)
                .context(request.context)
                .status(CommentChain.Status.OPEN)
                .build();
        try {
            return chainRepo.save(chain);
        } catch (MongoWriteException failure) {
            if (failure.getError().getCode() == 11000
                    && request.subject.getExternalEntity() != null) {
                var external = request.subject.getExternalEntity();
                List<CommentChain> existing = chainRepo.findByExternalSubject(
                        external.getSourceSystem(),
                        external.getEntityType(),
                        external.getExternalId());
                if (existing.size() == 1) {
                    return existing.get(0);
                }
            }
            throw failure;
        }
    }

    public CommentChain getChain(String chainId) {
        ObjectId id = objectId(chainId, "chainId");
        return chainRepo.findById(id).orElseThrow(() ->
                new CommentOperationException(
                        CommentOperationException.Code.CHAIN_NOT_FOUND,
                        "Comment chain not found: " + chainId));
    }

    public List<CommentChain> findExternalSubject(
            String sourceSystem,
            String entityType,
            String externalId) {
        if (clean(sourceSystem) == null || clean(entityType) == null || clean(externalId) == null) {
            throw new CommentOperationException(
                    CommentOperationException.Code.INVALID_REQUEST,
                    "sourceSystem, entityType, and externalId are required");
        }
        return chainRepo.findByExternalSubject(sourceSystem, entityType, externalId);
    }

    public Comment addComment(
            String chainId,
            CommentCreateRequest request,
            ActorReference actor) {
        requireActor(actor);
        CommentChain chain = getChain(chainId);
        if (chain.getStatus() != CommentChain.Status.OPEN) {
            throw new CommentOperationException(
                    CommentOperationException.Code.CHAIN_LOCKED,
                    "Comment chain is not open: " + chainId);
        }
        if (request == null || clean(request.body) == null) {
            throw new CommentOperationException(
                    CommentOperationException.Code.INVALID_REQUEST,
                    "Comment body is required");
        }
        ObjectId parentId = clean(request.parentCommentId) == null
                ? null
                : objectId(request.parentCommentId, "parentCommentId");
        String refName = "comment-" + UUID.randomUUID();
        Comment comment = Comment.builder()
                .refName(refName)
                .displayName(summary(request.body))
                .chainId(chain.getId())
                .parentCommentId(parentId)
                .body(request.body)
                .bodyFormat(request.bodyFormat == null ? Comment.BodyFormat.MARKDOWN : request.bodyFormat)
                .author(actor)
                .mediaReferences(request.mediaReferences == null ? List.of() : request.mediaReferences)
                .requestId(clean(request.requestId))
                .metadata(request.metadata)
                .state(Comment.State.ACTIVE)
                .build();
        return commentRepo.save(comment);
    }

    public List<Comment> listComments(String chainId, int limit) {
        CommentChain chain = getChain(chainId);
        return commentRepo.findByChain(chain.getId(), limit);
    }

    public List<Comment> listReplies(String chainId, String parentCommentId, int limit) {
        CommentChain chain = getChain(chainId);
        ObjectId parentId = objectId(parentCommentId, "parentCommentId");
        return commentRepo.findReplies(chain.getId(), parentId, limit);
    }

    private static void requireActor(ActorReference actor) {
        if (actor == null || clean(actor.getActorId()) == null) {
            throw new CommentOperationException(
                    CommentOperationException.Code.AUTHENTICATED_ACTOR_REQUIRED,
                    "An authenticated actor is required");
        }
    }

    private static ObjectId objectId(String value, String field) {
        try {
            return new ObjectId(value);
        } catch (RuntimeException failure) {
            throw new CommentOperationException(
                    CommentOperationException.Code.INVALID_ID,
                    field + " must be a Mongo ObjectId");
        }
    }

    private static String summary(String body) {
        String collapsed = body.trim().replaceAll("\\s+", " ");
        return collapsed.length() <= 80 ? collapsed : collapsed.substring(0, 77) + "...";
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.trim();
        return clean.isEmpty() ? null : clean;
    }
}
