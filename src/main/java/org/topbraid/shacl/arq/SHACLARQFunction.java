/*******************************************************************************
 * Copyright (c) 2009 TopQuadrant, Inc.
 * All rights reserved. 
 *******************************************************************************/
package org.topbraid.shacl.arq;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.jena.atlas.io.IndentedWriter;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.QuerySolutionMap;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sparql.core.DatasetImpl;
import org.apache.jena.sparql.core.Substitute;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprEvalException;
import org.apache.jena.sparql.expr.ExprList;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionEnv;
import org.apache.jena.sparql.serializer.SerializationContext;
import org.apache.jena.sparql.sse.SSE;
import org.apache.jena.sparql.util.ExprUtils;
import org.apache.jena.sparql.util.FmtUtils;
import org.topbraid.shacl.model.SHFunction;
import org.topbraid.shacl.model.SHParameter;
import org.topbraid.shacl.model.SHParameterizable;
import org.topbraid.shacl.vocabulary.DASH;
import org.topbraid.spin.arq.SPINFunctionFactory;
import org.topbraid.spin.statistics.SPINStatistics;
import org.topbraid.spin.statistics.SPINStatisticsManager;
import org.topbraid.spin.util.JenaDatatypes;
import org.topbraid.spin.util.JenaUtil;


/**
 * An ARQ function that is based on a SHACL function definition.
 * 
 * @author Holger Knublauch
 */
public abstract class SHACLARQFunction implements org.apache.jena.sparql.function.Function, SPINFunctionFactory {
	
	private boolean cachable;
	
	protected List<String> paramNames = new ArrayList<String>();
	
	private SHFunction shFunction;
	

	/**
	 * Constructs a new SHARQFunction based on a given sh:Function.
	 * The shaclFunction must be associated with the Model containing
	 * the triples of its definition.
	 * @param shaclFunction  the SHACL function
	 */
	protected SHACLARQFunction(SHFunction shaclFunction) {
		this.shFunction = shaclFunction;
		if(shaclFunction != null) {
			this.cachable = shaclFunction.hasProperty(DASH.cachable, JenaDatatypes.TRUE);
		}
	}


	protected void addParameters(SHParameterizable parameterizable) {
		JenaUtil.setGraphReadOptimization(true);
		try {
			for(SHParameter param : parameterizable.getOrderedParameters()) {
				String varName = param.getVarName();
				if(varName == null) {
					throw new IllegalStateException(param + " of " + parameterizable + " does not have a valid predicate");
				}
				paramNames.add(varName);
			}
		}
		finally {
			JenaUtil.setGraphReadOptimization(false);
		}
	}
	

	@Override
    public void build(String uri, ExprList args) {
	}

	
	@Override
    public org.apache.jena.sparql.function.Function create(String uri) {
		return this;
	}

	
	@Override
    public NodeValue exec(Binding binding, ExprList args, String uri, FunctionEnv env) {
		
		Graph activeGraph = env.getActiveGraph();
		Model model = activeGraph != null ? 
				ModelFactory.createModelForGraph(activeGraph) :
				ModelFactory.createDefaultModel();
		
		QuerySolutionMap bindings = new QuerySolutionMap();
		
		Node[] paramsForCache;
		if(cachable) {
			paramsForCache = new Node[args.size()];
		}
		else {
			paramsForCache = null;
		}
		for(int i = 0; i < args.size(); i++) {
			Expr expr = args.get(i);
			if(expr != null && (!expr.isVariable() || binding.contains(expr.asVar()))) {
	        	NodeValue x = expr.eval(binding, env);
	        	if(x != null) {
	        		String paramName;
	        		if(i < paramNames.size()) {
	        			paramName = paramNames.get(i);
	        		}
	        		else {
	        			paramName = "arg" + (i + 1);
	        		}
	        		bindings.add(paramName, model.asRDFNode(x.asNode()));
	        		if(cachable) {
	        			paramsForCache[i] = x.asNode();
	        		}
	        	}
			}
		}
		
		// TODO: Reactivate
		//if(SPINArgumentChecker.get() != null) {
		//	SPINArgumentChecker.get().check(shaclFunction, bindings);
		//}
		
		Dataset dataset = DatasetImpl.wrap(env.getDataset());
		
		if(SPINStatisticsManager.get().isRecording() && SPINStatisticsManager.get().isRecordingSPINFunctions()) {
			StringBuffer sb = new StringBuffer();
			sb.append("SHACL Function ");
			sb.append(SSE.str(NodeFactory.createURI(uri), model));
			sb.append("(");
			for(int i = 0; i < args.size(); i++) {
				if(i > 0) {
					sb.append(", ");
				}
				Expr expr = args.get(i);
				expr = Substitute.substitute(expr, binding);
				if(expr == null) {
					sb.append("?unbound");
				}
				else {
					ByteArrayOutputStream bos = new ByteArrayOutputStream();
					IndentedWriter iOut = new IndentedWriter(bos);
					ExprUtils.fmtSPARQL(iOut, expr, new SerializationContext(model));
					iOut.flush();
					sb.append(bos.toString());
				}
			}
			sb.append(")");
			long startTime = System.currentTimeMillis();
			NodeValue result;
			try {
				if(cachable) {
					result = SHACLFunctionsCache.get().execute(this, dataset, model, bindings, paramsForCache);
				}
				else {
					result = executeBody(dataset, model, bindings);
				}
				sb.append(" = ");
				sb.append(FmtUtils.stringForNode(result.asNode(), model));
			}
			catch(ExprEvalException ex) {
				sb.append(" : ");
				sb.append(ex.getLocalizedMessage());
				throw ex;
			}
			finally {
				long endTime = System.currentTimeMillis();
				SPINStatistics stats = new SPINStatistics(sb.toString(), getQueryString(), endTime - startTime, startTime, NodeFactory.createURI(uri));
				SPINStatisticsManager.get().addSilently(Collections.singleton(stats));
			}
			return result;
		}
		else {
			if(cachable) {
				return SHACLFunctionsCache.get().execute(this, dataset, model, bindings, paramsForCache);
			}
			else {
				return executeBody(dataset, model, bindings);
			}
		}
	}


	public abstract NodeValue executeBody(Dataset dataset, Model model, QuerySolution bindings);
	
	
	protected abstract String getQueryString();
	
	
	/**
	 * Gets the underlying sh:Function Model object for this ARQ function.
	 * @return the sh:Function (may be null)
	 */
	public SHFunction getSHACLFunction() {
		return shFunction;
	}
	
	
	/**
	 * Gets the names of the declared parameters, in order from left to right.
	 * @return the parameter names
	 */
	public String[] getParamNames() {
		return paramNames.toArray(new String[0]);
	}
}
