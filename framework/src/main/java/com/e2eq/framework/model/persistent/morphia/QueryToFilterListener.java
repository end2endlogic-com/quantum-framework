package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.grammar.BIAPIQueryBaseListener;
import com.e2eq.framework.grammar.BIAPIQueryParser;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import io.quarkus.logging.Log;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.Token;
import org.apache.commons.text.StringSubstitutor;
import com.e2eq.framework.model.security.PrincipalContext;
import com.e2eq.framework.model.security.ResourceContext;

import java.util.*;

public class QueryToFilterListener extends BIAPIQueryBaseListener {
   protected Stack<Filter> filterStack = new Stack<>();
   protected Stack<Integer> opTypeStack = new Stack<>();

   protected boolean complete = false;

   Map<String, String> variableMap = null;
   StringSubstitutor sub = null;


   public QueryToFilterListener(Map<String, String> variableMap, StringSubstitutor sub) {
      this.variableMap = variableMap;
      this.sub = sub;
   }

   public QueryToFilterListener(Map<String, String> variableMap) {
      this.variableMap = variableMap;
      sub = new StringSubstitutor(variableMap);
   }

   public QueryToFilterListener(PrincipalContext pcontext, ResourceContext rcontext) {
      this.variableMap = MorphiaUtils.createVariableMapFrom(pcontext, rcontext);
       sub = new StringSubstitutor(variableMap);
   }

   public QueryToFilterListener() {
   }

  public StringSubstitutor getSubstitutor() {return sub;}

   public Filter getFilter () {
      if (complete) {
         return filterStack.peek();
      } else {
         throw new IllegalStateException("Filter is incomplete");
      }
   }

   @Override
   public void enterQuery (BIAPIQueryParser.QueryContext ctx) {
      complete = false;
   }

   @Override
   public void exitQuery (BIAPIQueryParser.QueryContext ctx) {
      buildComposite();
      checkDone();
   }

   protected void checkDone () {
      // the only remaining thing in the stack should be what we want to return
      if (filterStack.size() != 1) {
         for (Filter f : filterStack) {
            Log.debug(f.toString());
         }
         throw new IllegalStateException("Criteria stack not 1 and end of build? size:" + filterStack.size());
      }

      if (Log.isDebugEnabled()) {
         Log.debug("-- Additional Filters based upon rules--");
         for (Filter f : filterStack) {
            Log.debug(f.toString());
         }
         Log.debug("--------");
      }

      complete = true;
   }

   protected void buildComposite () {
      List<Filter> andFilters = new ArrayList<>();
      List<Filter> orFilters = new ArrayList<>();
      Filter[] filterArray = new Filter[0];

      // Unwind the stacks
      while (!opTypeStack.isEmpty()) {
         int opType = opTypeStack.pop();
         switch (opType) {
            case BIAPIQueryParser.AND:
               //TODO: consider if or is already in progress or not like we are doing for and
               andFilters.add(filterStack.pop());
               break;
            case BIAPIQueryParser.OR:
               if (!andFilters.isEmpty()) {
                  andFilters.add(filterStack.pop());
                  orFilters.add(Filters.and(andFilters.toArray(filterArray)));
                  andFilters = new ArrayList<>();
               } else {
                  orFilters.add(filterStack.pop());
               }
               break;
            case BIAPIQueryParser.LPAREN:
               if (andFilters.isEmpty()) {
                  andFilters.add(filterStack.pop());
                  orFilters.add(Filters.and(andFilters.toArray(filterArray)));
                  andFilters = new ArrayList<>();
               } else {
                  orFilters.add(filterStack.pop());
               }
               if (orFilters.size() == 1) {
                  filterStack.push(orFilters.get(0));
               } else if (orFilters.size() > 1) {
                  filterStack.push(Filters.or(orFilters.toArray(filterArray)));
               } else {
                  throw new IllegalStateException("OrCriteria is empty expected to be not empty?");
               }
               orFilters = new ArrayList<>();
               break;
            default:
               throw new IllegalArgumentException("Unsupported operation:" + opType);

         }
      }
      if (!andFilters.isEmpty()) {
         andFilters.add(filterStack.pop());
         orFilters.add(Filters.and(andFilters.toArray(filterArray)));
      } else {
         orFilters.add(filterStack.pop());
      }
      if (orFilters.size() == 1) {
         filterStack.push(orFilters.get(0));
      } else if (orFilters.size() > 1) {
         filterStack.push(Filters.or(orFilters.toArray(filterArray)));
      }
   }

   @Override
   public void enterExistsExpr (BIAPIQueryParser.ExistsExprContext ctx) {
      if (Log.isDebugEnabled()) {
         Log.debug("enterExists:" + ctx.field.getText() + ctx.op.getText());
      }
      Filter f = Filters.exists(ctx.field.getText());
      filterStack.push(f);
   }

   @Override
   public void exitExistsExpr (BIAPIQueryParser.ExistsExprContext ctx) {

   }

   @Override
   public void enterExprGroup (BIAPIQueryParser.ExprGroupContext ctx) {
      opTypeStack.push(ctx.lp.getType());
   }

   @Override
   public void exitExprGroup (BIAPIQueryParser.ExprGroupContext ctx) {
      buildComposite();
   }


   @Override
   public void enterExprOp (BIAPIQueryParser.ExprOpContext ctx) {
      opTypeStack.push(ctx.op.getType());
   }

   @Override
   public void enterBooleanExpr (BIAPIQueryParser.BooleanExprContext ctx) {
      if (ctx.op.getType() == BIAPIQueryParser.EQ) {
         filterStack.push(Filters.eq(ctx.field.getText(), ctx.value.getText()));
      } else if (ctx.op.getType() == BIAPIQueryParser.NEQ) {
         filterStack.push(Filters.ne(ctx.field.getText(), ctx.value.getText()));
      } else {
         throw new IllegalArgumentException("Operator not recognized:" + ctx.op.getText());
      }
   }

   @Override
   public void enterInExpr (BIAPIQueryParser.InExprContext ctx) {
      // convert values array to individual values
      List<String> values = new ArrayList<>();
      StringTokenizer tokenizer = new StringTokenizer(ctx.value.getText().substring(1,
         ctx.value.getText().length() - 1), ",");
      while (tokenizer.hasMoreTokens()) {
         String value = (variableMap != null ) ? sub.replace(tokenizer.nextToken()) : tokenizer.nextToken();
         values.add(value);
      }
      Filter f = Filters.in(ctx.field.getText(), values);

      filterStack.push(f);
   }

   @Override
   public void enterValueListExpr (BIAPIQueryParser.ValueListExprContext ctx) {

   }

   @Override
   public void exitValueListExpr (BIAPIQueryParser.ValueListExprContext ctx) {

   }

   @Override
   public void enterStringExpr (BIAPIQueryParser.StringExprContext ctx) {
      Filter f = makeBasicFilter(ctx.field, ctx.op, ctx.value);
      if (f != null) {
         filterStack.push(f);
      } else {
         Log.warn("makeBasicFilter returned null ... should not happen?");
      }
   }

   protected Filter makeBasicFilter (Token field, Token op, Object value) {
      Filter filter = null;

      if (variableMap != null) {
         if ( ((CommonToken) value).getType() == BIAPIQueryParser.VARIABLE) {
            value = sub.replace(((CommonToken) value).getText());
         }
      }

      if (value instanceof CommonToken tok) {
         switch (tok.getType()) {
            case BIAPIQueryParser.STRING:
               value = tok.getText();
               break;
            case BIAPIQueryParser.QUOTED_STRING:
               value = tok.getText();
               break;
            case BIAPIQueryParser.VARIABLE:
               value = tok.getText();
               break;
            case BIAPIQueryParser.NUMBER: {
                String num = tok.getText();
                value = Double.parseDouble(num);
            }
               break;
            case BIAPIQueryParser.WHOLENUMBER: {
                String num = tok.getText();
                value = Long.parseLong(num);
            }
               break;
            default:
               throw new IllegalArgumentException("token:" + tok.getText() + " did not resolve to a known type");
         }
      }

      switch (op.getType()) {
         case BIAPIQueryParser.EQ:
            filter = Filters.eq(field.getText(), value);
            break;
         case BIAPIQueryParser.NEQ:
            filter = Filters.ne(field.getText(), value);
            break;
         case BIAPIQueryParser.GT:
            filter = Filters.gt(field.getText(), value);
            break;
         case BIAPIQueryParser.GTE:
            filter = Filters.gte(field.getText(), value);
            break;
         case BIAPIQueryParser.LT:
            filter = Filters.lt(field.getText(), value);
            break;
         case BIAPIQueryParser.LTE:
            filter = Filters.lte(field.getText(), value);
            break;
         default:
            throw new IllegalArgumentException("Operator invalid in this context:" + op.getText());
      }

      return filter;

   }


   @Override
   public void enterQuotedExpr (BIAPIQueryParser.QuotedExprContext ctx) {
      Filter f = makeBasicFilter(ctx.field, ctx.op, ctx.value);
      filterStack.push(f);
   }

   @Override
   public void enterNumberExpr (BIAPIQueryParser.NumberExprContext ctx) {
      Filter f = makeBasicFilter(ctx.field, ctx.op, ctx.value);
      filterStack.push(f);
   }

   @Override
   public void enterWholenumberExpr (BIAPIQueryParser.WholenumberExprContext ctx) {
      Filter f = makeBasicFilter(ctx.field, ctx.op, ctx.value);
      filterStack.push(f);
   }

}
