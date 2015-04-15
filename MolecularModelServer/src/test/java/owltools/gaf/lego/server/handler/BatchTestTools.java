package owltools.gaf.lego.server.handler;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import owltools.gaf.lego.IdStringManager.AnnotationShorthand;
import owltools.gaf.lego.json.JsonAnnotation;
import owltools.gaf.lego.json.JsonEvidenceInfo;
import owltools.gaf.lego.json.JsonOwlFact;
import owltools.gaf.lego.json.JsonOwlIndividual;
import owltools.gaf.lego.json.JsonOwlObject;
import owltools.gaf.lego.json.JsonOwlObject.JsonOwlObjectType;
import owltools.gaf.lego.json.JsonRelationInfo;
import owltools.gaf.lego.json.MolecularModelJsonRenderer;
import owltools.gaf.lego.server.handler.M3BatchHandler.Entity;
import owltools.gaf.lego.server.handler.M3BatchHandler.M3Argument;
import owltools.gaf.lego.server.handler.M3BatchHandler.M3BatchResponse;
import owltools.gaf.lego.server.handler.M3BatchHandler.M3BatchResponse.ResponseDataKey;
import owltools.gaf.lego.server.handler.M3BatchHandler.M3Request;
import owltools.gaf.lego.server.handler.M3BatchHandler.Operation;

public class BatchTestTools {

	static M3Request addIndividual(String modelId, String cls, JsonOwlObject...expressions) {
		M3Request r = new M3Request();
		r.entity = Entity.individual.name();
		r.operation = Operation.add.getLbl();
		r.arguments = new M3Argument();
		r.arguments.modelId = modelId;
		BatchTestTools.setExpressionClass(r.arguments, cls);
		if (expressions != null && expressions.length > 0) {
			JsonOwlObject[] temp = new JsonOwlObject[expressions.length+1];
			temp[0] = r.arguments.expressions[0];
			for (int i = 0; i < expressions.length; i++) {
				temp[i+1] = expressions[i];
			}
			r.arguments.expressions = temp;	
		}
		
		return r;
	}

	static M3Request removeIndividual(String modelId, String individual) {
		M3Request r = new M3Request();
		r.entity = Entity.individual.name();
		r.operation = Operation.remove.getLbl();
		r.arguments = new M3Argument();
		r.arguments.modelId = modelId;
		r.arguments.individual = individual;
		return r;
	}

	static M3Request addEdge(String modelId, String sub, String pred, String obj) {
		M3Request r = new M3Request();
		r.entity = Entity.edge.name();
		r.operation = Operation.add.getLbl();
		r.arguments = new M3Argument();
		r.arguments.modelId = modelId;
		r.arguments.subject = sub;
		r.arguments.predicate = pred;
		r.arguments.object = obj;
		return r;
	}

	static M3Request deleteEdge(String modelId, String sub, String pred, String obj) {
		M3Request r = new M3Request();
		r.entity = Entity.edge.name();
		r.operation = Operation.remove.getLbl();
		r.arguments = new M3Argument();
		r.arguments.modelId = modelId;
		r.arguments.subject = sub;
		r.arguments.predicate = pred;
		r.arguments.object = obj;
		return r;
	}

	static void setExpressionClass(M3Argument arg, String cls) {
		arg.expressions = new JsonOwlObject[1];
		arg.expressions[0] = new JsonOwlObject();
		arg.expressions[0].type = JsonOwlObjectType.Class;
		arg.expressions[0].id = cls;
	}
	
	static JsonOwlObject createClass(String cls) {
		JsonOwlObject json = new JsonOwlObject();
		json.type = JsonOwlObjectType.Class;
		json.id = cls;
		return json;
	}
	
	static JsonOwlObject createSvf(String prop, String filler) {
		JsonOwlObject json = new JsonOwlObject();
		json.type = JsonOwlObjectType.SomeValueFrom;
		json.onProperty = prop;
		json.filler = new JsonOwlObject();
		json.filler.type = JsonOwlObjectType.Class;
		json.filler.id = filler;
		return json;
	}

	static void printJson(Object resp) {
		String json = MolecularModelJsonRenderer.renderToJson(resp, true);
		System.out.println("---------");
		System.out.println(json);
		System.out.println("---------");
	}
	
	static JsonOwlIndividual[] responseIndividuals(M3BatchResponse response) {
		assertNotNull(response);
		assertNotNull(response.data);
		return (JsonOwlIndividual[]) response.data.get(ResponseDataKey.individuals);
	}
	
	static JsonOwlFact[] responseFacts(M3BatchResponse response) {
		assertNotNull(response);
		assertNotNull(response.data);
		return (JsonOwlFact[]) response.data.get(ResponseDataKey.facts);
	}
	
	static JsonOwlObject[] responseProperties(M3BatchResponse response) {
		assertNotNull(response);
		assertNotNull(response.data);
		return (JsonOwlObject[]) response.data.get(ResponseDataKey.properties);
	}
	
	static JsonOwlIndividual[] responseInferences(M3BatchResponse response) {
		assertNotNull(response);
		assertNotNull(response.data);
		return (JsonOwlIndividual[]) response.data.get(ResponseDataKey.individuals_i);
	}
	
	static JsonAnnotation[] responseAnnotations(M3BatchResponse response) {
		assertNotNull(response);
		assertNotNull(response.data);
		return (JsonAnnotation[]) response.data.get(ResponseDataKey.annotations);
	}
	
	static String responseId(M3BatchResponse response) {
		assertNotNull(response);
		assertNotNull(response.data);
		return (String) response.data.get(ResponseDataKey.id);
	}
	
	@SuppressWarnings("unchecked")
	static List<JsonRelationInfo> responseRelations(M3BatchResponse response) {
		assertNotNull(response);
		assertNotNull(response.data);
		return (List<JsonRelationInfo>) response.data.get(ResponseDataKey.relations);
	}
	
	static Boolean responseInconsistent(M3BatchResponse response) {
		assertNotNull(response);
		assertNotNull(response.data);
		return (Boolean) response.data.get(ResponseDataKey.inconsistent_p);
	}

	@SuppressWarnings("unchecked")
	static Map<String,Map<String,String>> responseModelsMeta(M3BatchResponse response) {
		assertNotNull(response);
		assertNotNull(response.data);
		return (Map<String,Map<String,String>>) response.data.get(ResponseDataKey.models_meta);
	}
	
	@SuppressWarnings("unchecked")
	static List<JsonEvidenceInfo> responseEvidences(M3BatchResponse response) {
		assertNotNull(response);
		assertNotNull(response.data);
		return (List<JsonEvidenceInfo>) response.data.get(ResponseDataKey.evidence);
	}
	
	//model_ids
	@SuppressWarnings("unchecked")
	static Set<String> responseModelsIds(M3BatchResponse response) {
		assertNotNull(response);
		assertNotNull(response.data);
		return (Set<String>) response.data.get(ResponseDataKey.model_ids);
	}
	
	static String responseExport(M3BatchResponse response) {
		assertNotNull(response);
		assertNotNull(response.data);
		return (String) response.data.get(ResponseDataKey.exportModel);
	}

	static String generateBlankModel(JsonOrJsonpBatchHandler handler) {
		// create blank model
		M3Request[] batch = new M3Request[1];
		batch[0] = new M3Request();
		batch[0].entity = Entity.model.name();
		batch[0].operation = Operation.generateBlank.getLbl();
		M3BatchResponse resp = handler.m3Batch(BatchModelHandlerTest.uid, BatchModelHandlerTest.intention, null, batch, true);
		assertEquals(resp.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, resp.message_type);
		assertNotNull(resp.packet_id);
		String modelId = responseId(resp);
		assertNotNull(modelId);
		return modelId;
	}
	
	static JsonAnnotation[] singleAnnotation(AnnotationShorthand sh, String value) {
		return new JsonAnnotation[]{ JsonAnnotation.create(sh, value)};
	}

}
