package owltools.gaf.lego.server.handler;

import static owltools.gaf.lego.server.handler.JsonOrJsonpModelHandler.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.log4j.Logger;
import org.glassfish.jersey.server.JSONP;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import owltools.gaf.lego.LegoModelGenerator;
import owltools.gaf.lego.MolecularModelJsonRenderer;
import owltools.gaf.lego.MolecularModelManager;
import owltools.graph.OWLGraphWrapper;

public class JsonOrJsonpBatchHandler implements M3BatchHandler {

	private static Logger LOG = Logger.getLogger(JsonOrJsonpBatchHandler.class);
	
	private final OWLGraphWrapper graph;
	private MolecularModelManager models = null;

	public JsonOrJsonpBatchHandler(OWLGraphWrapper graph, MolecularModelManager models) {
		super();
		this.graph = graph;
		this.models = models;
	}

	protected synchronized MolecularModelManager getMolecularModelManager() throws OWLOntologyCreationException {
		if (models == null) {
			LOG.info("Creating m3 object");
			models = new MolecularModelManager(graph);
		}
		return models;
	}

	@Override
	@JSONP(callback = JSONP_DEFAULT_CALLBACK, queryParam = JSONP_DEFAULT_OVERWRITE)
	public M3BatchResponse m3Batch(String uid, String intention, M3Request[] requests) {
		M3BatchResponse response = new M3BatchResponse(uid, intention);
		final Set<OWLNamedIndividual> relevantIndividuals = new HashSet<OWLNamedIndividual>();
		boolean renderBulk = false;
		String modelId = null;
		try {
			MolecularModelManager m3 = getMolecularModelManager();
			for (M3Request request : requests) {
				requireNotNull(request, "request");
				final String entity = StringUtils.trimToNull(request.entity);
				final String operation = StringUtils.trimToNull(request.operation);
				requireNotNull(request.arguments, "request.arguments");
				
				// individual
				if ("individual".equals(entity)) {
					modelId = checkModelId(modelId, request);
					
					// get info, no modification
					if ("get".equals(operation)) {
						requireNotNull(request.arguments.individual, "request.arguments.individual");
						OWLNamedIndividual i = m3.getNamedIndividual(modelId, request.arguments.individual);
						relevantIndividuals.add(i);
					}
					// create from class
					else if ("create".equals(operation)) {
						// required: subject
						// optional: expressions, values
						
						requireNotNull(request.arguments.subject, "request.arguments.subject");
						Collection<Pair<String, String>> annotations = extract(request.arguments.values);
						Pair<String, OWLNamedIndividual> individualPair = m3.createIndividualNonReasoning(modelId, request.arguments.subject, annotations);
						relevantIndividuals.add(individualPair.getValue());
						
						for(M3Expression expression : request.arguments.expressions) {
							requireNotNull(expression.type, "expression.type");
							requireNotNull(expression.literal, "expression.literal");
							if ("class".equals(expression.type)) {
								m3.addTypeNonReasoning(modelId, individualPair.getKey(), expression.literal);
							}
							else if ("svf".equals(expression.type)) {
								requireNotNull(expression.onProp, "expression.onProp");
								m3.addTypeNonReasoning(modelId, individualPair.getKey(), expression.onProp, expression.literal);
							}
						}
					}
					// remove individual (and all axioms using it)
					else if ("remove".equals(operation)){
						// required: modelId, individual
						
						requireNotNull(request.arguments.individual, "request.arguments.individual");
						m3.deleteIndividual(modelId, request.arguments.individual);
						renderBulk = true;
					}				
					// add type / named class assertion
					else if ("add-type".equals(operation)){
						// required: individual, expressions
						
						requireNotNull(request.arguments.individual, "request.arguments.individual");
						requireNotNull(request.arguments.expressions, "request.arguments.expressions");
						for(M3Expression expression : request.arguments.expressions) {
							requireNotNull(expression.type, "expression.type");
							requireNotNull(expression.literal, "expression.literal");
							if ("class".equals(expression.type)) {
								OWLNamedIndividual i = m3.addTypeNonReasoning(modelId, 
										request.arguments.individual, expression.literal);
								relevantIndividuals.add(i);
							}
							else if ("svf".equals(expression.type)) {
								requireNotNull(expression.onProp, "expression.onProp");
								OWLNamedIndividual i = m3.addTypeNonReasoning(modelId,
										request.arguments.individual, expression.onProp, expression.literal);
								relevantIndividuals.add(i);
							}
						}
					}
					// remove type / named class assertion
					else if ("remove-type".equals(operation)){
						// required: individual, expressions
						requireNotNull(request.arguments.individual, "request.arguments.individual");
						requireNotNull(request.arguments.expressions, "request.arguments.expressions");
						for(M3Expression expression : request.arguments.expressions) {
							requireNotNull(expression.type, "expression.type");
							requireNotNull(expression.literal, "expression.literal");
							if ("class".equals(expression.type)) {
								OWLNamedIndividual i = m3.removeTypeNonReasoning(modelId,
										request.arguments.individual, expression.literal);
								relevantIndividuals.add(i);
							}
							else if ("svf".equals(expression.type)) {
								requireNotNull(expression.onProp, "expression.onProp");
								OWLNamedIndividual i = m3.removeTypeNonReasoning(modelId,
										request.arguments.individual, expression.onProp, expression.literal);
								relevantIndividuals.add(i);
							}
						}
					}
					// add annotation
					else if ("add-annotation".equals(operation)){
						// required: individual, values
						requireNotNull(request.arguments.individual, "request.arguments.individual");
						requireNotNull(request.arguments.values, "request.arguments.values");
						
						OWLNamedIndividual i = m3.addAnnotations(modelId, request.arguments.individual,
								extract(request.arguments.values));
						relevantIndividuals.add(i);
					}
					// remove annotation
					else if ("remove-annotation".equals(operation)){
						// required: individual, values
						requireNotNull(request.arguments.individual, "request.arguments.individual");
						requireNotNull(request.arguments.values, "request.arguments.values");
						
						OWLNamedIndividual i = m3.removeAnnotations(modelId, request.arguments.individual,
								extract(request.arguments.values));
						relevantIndividuals.add(i);
					}
					else {
						return error(response, null, "Unknown operation: "+operation);
					}
				}
				// edge
				else if ("edge".equals(entity)) {
					modelId = checkModelId(modelId, request);
					// required: subject, predicate, object
					requireNotNull(request.arguments.subject, "request.arguments.subject");
					requireNotNull(request.arguments.predicate, "request.arguments.predicate");
					requireNotNull(request.arguments.object, "request.arguments.object");
					
					// add edge
					if ("add".equals(operation)){
						// optional: values
						List<OWLNamedIndividual> individuals = m3.addFactNonReasoning(modelId,
								request.arguments.predicate, request.arguments.subject,
								request.arguments.object, extract(request.arguments.values));
						relevantIndividuals.addAll(individuals);
					}
					// remove edge
					else if ("remove".equals(operation)){
						List<OWLNamedIndividual> individuals = m3.removeFactNonReasoning(modelId,
								request.arguments.predicate, request.arguments.subject,
								request.arguments.object);
						relevantIndividuals.addAll(individuals);
					}
					// add annotation
					else if ("add-annotation".equals(operation)){
						requireNotNull(request.arguments.values, "request.arguments.values");
						
						List<OWLNamedIndividual> individuals = m3.addAnnotations(modelId,
								request.arguments.predicate, request.arguments.subject,
								request.arguments.object, extract(request.arguments.values));
						relevantIndividuals.addAll(individuals);
					}
					// remove annotation
					else if ("remove-annotation".equals(operation)){
						requireNotNull(request.arguments.values, "request.arguments.values");
						
						List<OWLNamedIndividual> individuals = m3.removeAnnotations(modelId,
								request.arguments.predicate, request.arguments.subject,
								request.arguments.object, extract(request.arguments.values));
						relevantIndividuals.addAll(individuals);
					}
					else {
						return error(response, null, "Unknown operation: "+operation);
					}
				}
				//model
				else if ("model".equals(entity)) {
					// get model
					if ("get".equals(operation)){
						modelId = checkModelId(modelId, request);
						renderBulk = true;
					}
					else if ("generate".equals(operation)) {
						requireNotNull(request.arguments.db, "request.arguments.db");
						requireNotNull(request.arguments.subject, "request.arguments.subject");
						renderBulk = true;
						modelId = m3.generateModel(request.arguments.subject, request.arguments.db);
					}
					else if ("generate-blank".equals(operation)) {
						renderBulk = true;
						requireNotNull(request.arguments.db, "request.arguments.db");
						modelId = m3.generateBlankModel(request.arguments.db);
					}
					else if ("export".equals(operation)) {
						if (requests.length > 1) {
							// cannot be used with other requests in batch mode, would lead to conflicts in the returned signal
							return error(response, null, "Export model cannot be combined with other operations.");
						}
						modelId = checkModelId(modelId, request);
						return export(response, modelId, m3);
					}
					else if ("import".equals(operation)) {
						requireNotNull(request.arguments.importModel, "request.arguments.importModel");
						modelId = m3.importModel(request.arguments.importModel);
						renderBulk = true;
					}
					else if ("all-modelIds".equals(operation)) {
						if (requests.length > 1) {
							// cannot be used with other requests in batch mode, would lead to conflicts in the returned signal
							return error(response, null, operation+" cannot be combined with other operations.");
						}
						return allModelIds(response, m3);
					}
					else {
						return error(response, null, "Unknown operation: "+operation);
					}
				}
				// relations
				else if ("relations".equals(entity)) {
					if ("get".equals(operation)){
						if (requests.length > 1) {
							// cannot be used with other requests in batch mode, would lead to conflicts in the returned signal
							return error(response, null, "Get Relations cannot be combined with other operations.");
						}
						return relations(response, m3);
					}
					else {
						return error(response, null, "Unknown operation: "+operation);
					}
				}
				else {
					return error(response, null, "Unknown entity: "+entity);
				}
			}
			if (modelId == null) {
				return error(response, null, "Empty batch calls are not supported, at least one request is required.");
			}
			// get model
			final LegoModelGenerator model = m3.getModel(modelId);
			// update reasoner
			// report state
			final OWLReasoner reasoner = model.getReasoner();
			reasoner.flush();
			final boolean isConsistent = reasoner.isConsistent();
			
			// create response.data
			if (renderBulk) {
				// render complete model
				response.data = m3.getModelObject(modelId);
				response.signal = "rebuild";
			}
			else {
				// render individuals
				MolecularModelJsonRenderer renderer = new MolecularModelJsonRenderer(model.getAboxOntology());
				response.data = renderer.renderIndividuals(relevantIndividuals);
				response.signal = "merge";
			}
			
			// add other infos to data
			response.data.put("id", modelId);
			if (!isConsistent) {
				response.data.put("inconsistent_p", Boolean.TRUE);
			}
			response.message_type = "success";
			return response;
		} catch (Exception e) {
			return error(response, e, "Could not successfully complete batch request.");
		}
	}

	private M3BatchResponse allModelIds(M3BatchResponse response, MolecularModelManager m3) throws IOException {
		Set<String> allModelIds = m3.getAvailableModelIds();
		//Set<String> scratchModelIds = mmm.getScratchModelIds();
		//Set<String> storedModelIds = mmm.getStoredModelIds();
		//Set<String> memoryModelIds = mmm.getCurrentModelIds();

		Map<Object, Object> map = new HashMap<Object, Object>();
		map.put("models_all", allModelIds);
		//map.put("models_memory", memoryModelIds);
		//map.put("models_stored", storedModelIds);
		//map.put("models_scratch", scratchModelIds);
		
		response.message_type = "success";
		response.signal = "meta";
		response.data = map;
		return response;
	}

	private M3BatchResponse relations(M3BatchResponse response, MolecularModelManager m3) throws OWLOntologyCreationException {
		List<Map<Object,Object>> relList = MolecularModelJsonRenderer.renderRelations(m3);
		Map<Object, Object> relData = new HashMap<Object, Object>();
		relData.put("relations", relList);
		response.message_type = "success";
		response.signal = "meta";
		response.data = relData;
		return response;
	}
	
	private M3BatchResponse export(M3BatchResponse response, String modelId, MolecularModelManager m3) throws OWLOntologyStorageException {
		String exportModel = m3.exportModel(modelId);
		response.message_type = "success";
		response.signal = "meta";
		response.data = Collections.<Object, Object>singletonMap("export", exportModel);
		return response;
	}

	/**
	 * @param modelId
	 * @param request
	 * @return modelId
	 * @throws MissingParameterException
	 * @throws MultipleModelIdsParameterException
	 */
	public String checkModelId(String modelId, M3Request request) 
			throws MissingParameterException, MultipleModelIdsParameterException {
		final String currentModelId = request.arguments.modelId;
		requireNotNull(currentModelId, "request.arguments.modelId");
		if (modelId == null) {
			modelId = currentModelId;
		}
		else {
			if (modelId.equals(currentModelId) == false) {
				throw new MultipleModelIdsParameterException("Using multiple modelIds in one batch call is not supported.");
			}
		}
		return modelId;
	}
	
	private Collection<Pair<String, String>> extract(M3Pair[] values) {
		Collection<Pair<String, String>> result = null;
		if (values != null && values.length > 0) {
			result = new ArrayList<Pair<String,String>>();
			for (M3Pair m3Pair : values) {
				if (m3Pair.key != null && m3Pair.value != null) {
					result.add(Pair.of(m3Pair.key, m3Pair.value));
				}
			}
		}
		return result;
	}

	private M3BatchResponse error(M3BatchResponse state, Exception e, String msg) {
		state.message_type = "error";
		state.message = msg;
		if (e != null) {
			state.commentary = new HashMap<String, Object>();
			state.commentary.put("exception", e.getClass().getName());
			state.commentary.put("exceptionMsg", e.getMessage());
			StringWriter stacktrace = new StringWriter();
			e.printStackTrace(new PrintWriter(stacktrace));
			state.commentary.put("exceptionTrace", stacktrace.toString());
		}
		return state;
	}
	
	private void requireNotNull(Object value, String msg) throws MissingParameterException {
		if (value == null) {
			throw new MissingParameterException("Expected non-null value for: "+msg);
		}
	}
	
	private static class MissingParameterException extends Exception {

		private static final long serialVersionUID = 4362299465121954598L;

		/**
		 * @param message
		 */
		public MissingParameterException(String message) {
			super(message);
		}
		
	}
	
	private static class MultipleModelIdsParameterException extends Exception {

		private static final long serialVersionUID = 4362299465121954598L;

		/**
		 * @param message
		 */
		public MultipleModelIdsParameterException(String message) {
			super(message);
		}
		
	}
}
