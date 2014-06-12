package owltools.gaf.lego.server.handler;

import static org.junit.Assert.*;
import static owltools.gaf.lego.MolecularModelJsonRenderer.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import owltools.gaf.bioentities.ProteinTools;
import owltools.gaf.lego.LegoModelGenerator;
import owltools.gaf.lego.ManchesterSyntaxTool;
import owltools.gaf.lego.MolecularModelJsonRenderer;
import owltools.gaf.lego.MolecularModelManager;
import owltools.gaf.lego.MolecularModelManager.LegoAnnotationType;
import owltools.gaf.lego.server.handler.M3BatchHandler.Entity;
import owltools.gaf.lego.server.handler.M3BatchHandler.M3Argument;
import owltools.gaf.lego.server.handler.M3BatchHandler.M3BatchResponse;
import owltools.gaf.lego.server.handler.M3BatchHandler.M3Expression;
import owltools.gaf.lego.server.handler.M3BatchHandler.M3ExpressionType;
import owltools.gaf.lego.server.handler.M3BatchHandler.M3Pair;
import owltools.gaf.lego.server.handler.M3BatchHandler.M3Request;
import owltools.gaf.lego.server.handler.M3BatchHandler.Operation;
import owltools.graph.OWLGraphWrapper;
import owltools.io.ParserWrapper;

@SuppressWarnings({"unchecked", "rawtypes"})
public class BatchModelHandlerTest {
	
	@Rule
    public TemporaryFolder folder = new TemporaryFolder();
	
	private static JsonOrJsonpBatchHandler handler = null;
	private static MolecularModelManager models = null;

	private static final String uid = "test-user";
	private static final String intention = "test-intention";
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		ParserWrapper pw = new ParserWrapper();
		OWLGraphWrapper graph = pw.parseToOWLGraph("http://purl.obolibrary.org/obo/go.owl");
		models = new MolecularModelManager(graph);
		models.addImports(Arrays.asList("http://purl.obolibrary.org/obo/go/extensions/x-disjoint.owl"));
		models.setPathToGafs("src/test/resources/gaf");
		models.setPathToProteinFiles("src/test/resources/ontology/protein/subset");
		models.setDbToTaxon(ProteinTools.getDefaultDbToTaxon());
		handler = new JsonOrJsonpBatchHandler(models, Collections.singleton("part_of"));
		JsonOrJsonpBatchHandler.ADD_INFERENCES = true;
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		if (handler != null) {
			handler = null;
		}
		if (models != null) {
			models.dispose();
		}
	}
	
	@Test
	public void test() throws Exception {
		final String modelId = generateBlankModel();
		
		// create two individuals
		M3Request[] batch2 = new M3Request[2];
		batch2[0] = new M3Request();
		batch2[0].entity = Entity.individual.name();
		batch2[0].operation = Operation.create.getLbl();
		batch2[0].arguments = new M3Argument();
		batch2[0].arguments.modelId = modelId;
		batch2[0].arguments.subject = "GO:0006915"; // apoptotic process
		batch2[0].arguments.expressions = new M3Expression[2];
		batch2[0].arguments.expressions[0] = new M3Expression();
		batch2[0].arguments.expressions[0].type = "svf";
		batch2[0].arguments.expressions[0].onProp = "BFO:0000066"; // occurs_in
		batch2[0].arguments.expressions[0].literal = "GO:0005623"; // cell

		batch2[0].arguments.expressions[1] = new M3Expression();
		batch2[0].arguments.expressions[1].type = "svf";
		batch2[0].arguments.expressions[1].onProp = "RO:0002333"; // enabled_by
		batch2[0].arguments.expressions[1].literal = "UniProtKB:P0001"; // fake
		
		batch2[0].arguments.values = new M3Pair[2];
		batch2[0].arguments.values[0] = new M3Pair();
		batch2[0].arguments.values[0].key = LegoAnnotationType.comment.name();
		batch2[0].arguments.values[0].value = "comment 1";
		batch2[0].arguments.values[1] = new M3Pair();
		batch2[0].arguments.values[1].key = LegoAnnotationType.comment.name();
		batch2[0].arguments.values[1].value = "comment 2";
		
		batch2[1] = new M3Request();
		batch2[1].entity = Entity.individual.name();
		batch2[1].operation = Operation.create.getLbl();
		batch2[1].arguments = new M3Argument();
		batch2[1].arguments.modelId = modelId;
		batch2[1].arguments.subject = "GO:0043276"; // anoikis
		batch2[1].arguments.expressions = new M3Expression[1];
		batch2[1].arguments.expressions[0] = new M3Expression();
		batch2[1].arguments.expressions[0].type = M3ExpressionType.svf.getLbl();
		batch2[1].arguments.expressions[0].onProp = "RO:0002333"; // enabled_by
		batch2[1].arguments.expressions[0].literal = "GO:0043234 and (('has part' some UniProtKB:P0002) OR ('has part' some UniProtKB:P0003))";
		
		M3BatchResponse resp2 = handler.m3Batch(uid, intention, batch2);
		assertEquals(resp2.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, resp2.message_type);
		String individual1 = null;
		String individual2 = null;
		List<Map<Object, Object>> iObjs = (List) resp2.data.get(KEY_INDIVIDUALS);
		assertEquals(2, iObjs.size());
		for(Map<Object, Object> iObj : iObjs) {
			String id = (String) iObj.get(KEY.id);
			if (id.contains("6915")) {
				individual1 = id;
			}
			else {
				individual2 = id;
			}
		}
		
		// create fact
		M3Request[] batch3 = new M3Request[1];
		batch3[0] = new M3Request();
		batch3[0].entity = Entity.edge.name();
		batch3[0].operation = Operation.add.getLbl();
		batch3[0].arguments = new M3Argument();
		batch3[0].arguments.modelId = modelId;
		batch3[0].arguments.subject = individual1;
		batch3[0].arguments.object = individual2;
		batch3[0].arguments.predicate = "BFO:0000050"; // part_of
		
		M3BatchResponse resp3 = handler.m3Batch(uid, intention, batch3);
		assertEquals(resp3.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, resp3.message_type);
		
		// delete complex expression type
		M3Request[] batch4 = new M3Request[1];
		batch4[0] = new M3Request();
		batch4[0].entity = Entity.individual.name();
		batch4[0].operation = Operation.removeType.getLbl();
		batch4[0].arguments = new M3Argument();
		batch4[0].arguments.modelId = modelId;
		batch4[0].arguments.individual = individual2;
		batch4[0].arguments.expressions = new M3Expression[1];
		batch4[0].arguments.expressions[0] = new M3Expression();
		batch4[0].arguments.expressions[0].type = M3ExpressionType.svf.getLbl();
		batch4[0].arguments.expressions[0].onProp = "RO:0002333"; // enabled_by
		// "GO:0043234 and (('has part' some UniProtKB:P0002) OR ('has part' some UniProtKB:P0003))";
		batch4[0].arguments.expressions[0].expressions = new M3Expression[2];

		// GO:0043234
		batch4[0].arguments.expressions[0].expressions[0] = new M3Expression();
		batch4[0].arguments.expressions[0].expressions[0].type = M3ExpressionType.clazz.getLbl();
		batch4[0].arguments.expressions[0].expressions[0].literal = "GO:0043234";

		//'has part' some UniProtKB:P0002
		batch4[0].arguments.expressions[0].expressions[1] = new M3Expression();
		batch4[0].arguments.expressions[0].expressions[1].type = M3ExpressionType.union.getLbl();
		batch4[0].arguments.expressions[0].expressions[1].expressions = new M3Expression[2];
		
		batch4[0].arguments.expressions[0].expressions[1].expressions[0] = new M3Expression();
		batch4[0].arguments.expressions[0].expressions[1].expressions[0].type = M3ExpressionType.svf.getLbl();
		batch4[0].arguments.expressions[0].expressions[1].expressions[0].onProp = "BFO:0000051"; // has_part
		batch4[0].arguments.expressions[0].expressions[1].expressions[0].literal = "UniProtKB:P0002";
		
		// 'has part' some UniProtKB:P0003
		batch4[0].arguments.expressions[0].expressions[1].expressions[1] = new M3Expression();
		batch4[0].arguments.expressions[0].expressions[1].expressions[1].type = M3ExpressionType.svf.getLbl();
		batch4[0].arguments.expressions[0].expressions[1].expressions[1].onProp = "BFO:0000051"; // has_part
		batch4[0].arguments.expressions[0].expressions[1].expressions[1].literal = "UniProtKB:P0003";
		
		M3BatchResponse resp4 = handler.m3Batch(uid, intention, batch4);
		assertEquals(resp4.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, resp4.message_type);
		List<Map<Object, Object>> iObjs4 = (List) resp4.data.get(KEY_INDIVIDUALS);
		assertEquals(1, iObjs4.size());
		List<Map> types = (List<Map>) iObjs4.get(0).get(KEY.type);
		assertEquals(1, types.size());
	}
	
	@Test
	public void testParseComplex() throws Exception {
		String modelId = models.generateBlankModel(null);
		LegoModelGenerator model = models.getModel(modelId);
		OWLGraphWrapper graph = new OWLGraphWrapper(model.getAboxOntology());
		ManchesterSyntaxTool tool = new ManchesterSyntaxTool(graph, true);
		
		String expr = "GO:0043234 and ('has part' some UniProtKB:P0002) and ('has part' some UniProtKB:P0003)";
		
		OWLClassExpression clsExpr = tool.parseManchesterExpression(expr);
		assertNotNull(clsExpr);
	}
	
	@Test
	public void testParseComplexOr() throws Exception {
		final String modelId = models.generateBlankModel(null);
		
		M3Expression expression = new M3Expression();
		expression.type = M3ExpressionType.svf.getLbl();
		expression.onProp = "RO:0002333"; // enabled_by
		expression.literal = "('has part' some UniProtKB:F1NGQ9) or ('has part' some UniProtKB:F1NJN0)";
		
		OWLClassExpression ce = M3ExpressionParser.parse(modelId, expression, models);
		assertNotNull(ce);
	}
	
	@Test
	public void testModelAnnotations() throws Exception {
		final String modelId = generateBlankModel();
		
		// create annotations
		M3Request[] batch1 = new M3Request[1];
		batch1[0] = new M3Request();
		batch1[0].entity = Entity.model.name();
		batch1[0].operation = Operation.addAnnotation.getLbl();
		batch1[0].arguments = new M3Argument();
		batch1[0].arguments.modelId = modelId;

		batch1[0].arguments.values = new M3Pair[2];
		batch1[0].arguments.values[0] = new M3Pair();
		batch1[0].arguments.values[0].key = LegoAnnotationType.comment.name();
		batch1[0].arguments.values[0].value = "comment 1";
		batch1[0].arguments.values[1] = new M3Pair();
		batch1[0].arguments.values[1].key = LegoAnnotationType.comment.name();
		batch1[0].arguments.values[1].value = "comment 2";
		
		M3BatchResponse resp1 = handler.m3Batch(uid, intention, batch1);
		assertEquals(resp1.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, resp1.message_type);
		
		
		Map<Object, Object> data = models.getModelObject(modelId);
		List annotations = (List) data.get("annotations");
		assertNotNull(annotations);
		assertEquals(3, annotations.size());
		
		
		// remove one annotation
		M3Request[] batch2 = new M3Request[1];
		batch2[0] = new M3Request();
		batch2[0].entity = Entity.model.name();
		batch2[0].operation = Operation.removeAnnotation.getLbl();
		batch2[0].arguments = new M3Argument();
		batch2[0].arguments.modelId = modelId;

		batch2[0].arguments.values = new M3Pair[1];
		batch2[0].arguments.values[0] = new M3Pair();
		batch2[0].arguments.values[0].key = LegoAnnotationType.comment.name();
		batch2[0].arguments.values[0].value = "comment 1";

		M3BatchResponse resp2 = handler.m3Batch(uid, intention, batch2);
		assertEquals(resp2.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, resp2.message_type);
		
		Map<Object, Object> data2 = models.getModelObject(modelId);
		List annotations2 = (List) data2.get("annotations");
		assertNotNull(annotations2);
		assertEquals(2, annotations2.size());
	}
	
	@Test
	public void testMultipleMeta() throws Exception {
		models.setPathToOWLFiles(folder.newFolder().getCanonicalPath());
		models.dispose();
		
		M3Request[] requests = new M3Request[3];
		// get relations
		requests[0] = new M3Request();
		requests[0].entity = Entity.relations.name();
		requests[0].operation = Operation.get.getLbl();
		// get evidences
		requests[1] = new M3Request();
		requests[1].entity = Entity.evidence.name();
		requests[1].operation = Operation.get.getLbl();
		// get model ids
		requests[2] = new M3Request();
		requests[2].entity = Entity.model.name();
		requests[2].operation = Operation.allModelIds.getLbl();
		
		M3BatchResponse response = handler.m3Batch(uid, intention, requests);
		assertEquals(uid, response.uid);
		assertEquals(intention, response.intention);
		assertEquals(M3BatchResponse.MESSAGE_TYPE_SUCCESS, response.message_type);
		final List<Map<String, Object>> relations = (List)((Map) response.data).get("relations");
		boolean hasPartOf = false;
		for (Map<String, Object> map : relations) {
			String id = (String)map.get("id");
			assertNotNull(id);
			if ("part_of".equals(id)) {
				assertEquals("true", map.get("relevant"));
				hasPartOf = true;
			}
		}
		assertTrue(relations.size() > 100);
		assertTrue(hasPartOf);

		final List<Map<String, Object>> evidences = (List)((Map) response.data).get("evidence");
		assertTrue(evidences.size() > 100);
		
		final Set<String> modelIds = (Set)((Map) response.data).get("model_ids");
		assertEquals(0, modelIds.size());
	}

	@Test
	public void testProteinNames() throws Exception {
		
		M3Request[] batch1 = new M3Request[1];
		batch1[0] = new M3Request();
		batch1[0].entity = Entity.model.name();
		batch1[0].operation = Operation.generateBlank.getLbl();
		batch1[0].arguments = new M3Argument();
		batch1[0].arguments.db = "goa_chicken";
		
		M3BatchResponse response1 = handler.m3Batch(uid, intention, batch1);
		assertEquals(uid, response1.uid);
		assertEquals(intention, response1.intention);
		assertEquals(M3BatchResponse.MESSAGE_TYPE_SUCCESS, response1.message_type);
		final String modelId = (String) response1.data.get("id");
		
		// check model for imports
		LegoModelGenerator model = models.getModel(modelId);
		assertNotNull(model);
		OWLOntology aBox = model.getAboxOntology();
		Set<OWLOntology> closure = aBox.getImportsClosure();
		boolean found = false;
		for (OWLOntology ont : closure) {
			OWLOntologyID id = ont.getOntologyID();
			IRI iri = id.getOntologyIRI();
			if (iri.toString().endsWith("/9031.owl")) {
				found = true;
			}
		}
		assertTrue(found);
		
		// check that id resolves to a class and has the expected label
		final String proteinId = "UniProtKB:F1NGQ9";
		final String proteinLabel = "FZD1";
		OWLGraphWrapper g = new OWLGraphWrapper(aBox);
		OWLClass cls = g.getOWLClassByIdentifier(proteinId);
		assertNotNull(cls);
		assertEquals(proteinLabel, g.getLabel(cls));
		
		// try to generate a model with a protein and protein label
		M3Request[] batch2 = new M3Request[1];
		batch2[0] = new M3Request();
		batch2[0].entity = Entity.individual.name();
		batch2[0].operation = Operation.create.getLbl();
		batch2[0].arguments = new M3Argument();
		batch2[0].arguments.modelId = modelId;
		batch2[0].arguments.subject = "GO:0006915"; // apoptotic process
		batch2[0].arguments.expressions = new M3Expression[1];
		batch2[0].arguments.expressions[0] = new M3Expression();
		batch2[0].arguments.expressions[0].type = "svf";
		batch2[0].arguments.expressions[0].onProp = "RO:0002333"; // enabled_by
		batch2[0].arguments.expressions[0].literal = proteinId;
		
		M3BatchResponse response2 = handler.m3Batch(uid, intention, batch2);
		assertEquals(uid, response2.uid);
		assertEquals(intention, response2.intention);
		assertEquals(response2.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, response2.message_type);
		List<Map<Object, Object>> iObjs = (List) response2.data.get(KEY_INDIVIDUALS);
		assertEquals(1, iObjs.size());
		Map<Object, Object> individual = iObjs.get(0);
		Map onProperty = (Map)((List) individual.get(KEY.type)).get(1);
		Map svf = (Map) onProperty.get(KEY.someValuesFrom);
		assertEquals(proteinId, svf.get(KEY.id));
		assertEquals(proteinLabel, svf.get(KEY.label));
	}
	
	@Test
	public void testCreateBlankModelFromGAF() throws Exception {
		models.dispose();
		
		M3Request[] batch1 = new M3Request[1];
		batch1[0] = new M3Request();
		batch1[0].entity = Entity.model.name();
		batch1[0].operation = Operation.generateBlank.getLbl();
		batch1[0].arguments = new M3Argument();
		batch1[0].arguments.db = "goa_chicken";
		
		M3BatchResponse response1 = handler.m3Batch(uid, intention, batch1);
		assertEquals(uid, response1.uid);
		assertEquals(intention, response1.intention);
		assertEquals(M3BatchResponse.MESSAGE_TYPE_SUCCESS, response1.message_type);
		final String modelId1 = (String) response1.data.get("id");
		
		M3Request[] batch2 = new M3Request[1];
		batch2[0] = new M3Request();
		batch2[0].entity = Entity.model.name();
		batch2[0].operation = Operation.generateBlank.getLbl();
		batch2[0].arguments = new M3Argument();
		batch2[0].arguments.db = "goa_chicken";
		
		M3BatchResponse response2 = handler.m3Batch(uid, intention, batch2);
		assertEquals(uid, response2.uid);
		assertEquals(intention, response2.intention);
		assertEquals(response2.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, response2.message_type);
		final String modelId2 = (String) response2.data.get("id");
		
		assertNotEquals(modelId1, modelId2);
		
		M3Request[] batch3 = new M3Request[1];
		batch3[0] = new M3Request();
		batch3[0].entity = Entity.model.name();
		batch3[0].operation = Operation.generateBlank.getLbl();
		batch3[0].arguments = new M3Argument();
		batch3[0].arguments.db = "jcvi";
		
		M3BatchResponse response3 = handler.m3Batch(uid, intention, batch3);
		assertEquals(uid, response3.uid);
		assertEquals(intention, response3.intention);
		assertEquals(response3.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, response3.message_type);
		final String modelId3 = (String) response3.data.get("id");
		
		assertNotEquals(modelId1, modelId3);
		assertNotEquals(modelId2, modelId3);
	}
	
	@Test
	public void testCreateModelFromGAF() throws Exception {
		models.dispose();
		
		M3Request[] batch1 = new M3Request[1];
		batch1[0] = new M3Request();
		batch1[0].entity = Entity.model.name();
		batch1[0].operation = Operation.generate.getLbl();
		batch1[0].arguments = new M3Argument();
		batch1[0].arguments.db = "goa_chicken";
		batch1[0].arguments.subject = "GO:0004637";
		
		M3BatchResponse response1 = handler.m3Batch(uid, intention, batch1);
		assertEquals(uid, response1.uid);
		assertEquals(intention, response1.intention);
		assertEquals(response1.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, response1.message_type);
		final String modelId1 = (String) response1.data.get("id");
		
		M3Request[] batch2 = new M3Request[1];
		batch2[0] = new M3Request();
		batch2[0].entity = Entity.model.name();
		batch2[0].operation = Operation.generate.getLbl();
		batch2[0].arguments = new M3Argument();
		batch2[0].arguments.db = "goa_chicken";
		batch2[0].arguments.subject = "GO:0005509";
		
		M3BatchResponse response2 = handler.m3Batch(uid, intention, batch2);
		assertEquals(uid, response2.uid);
		assertEquals(intention, response2.intention);
		assertEquals(response2.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, response2.message_type);
		final String modelId2 = (String) response2.data.get("id");
		
		assertNotEquals(modelId1, modelId2);
		
		M3Request[] batch3 = new M3Request[1];
		batch3[0] = new M3Request();
		batch3[0].entity = Entity.model.name();
		batch3[0].operation = Operation.generate.getLbl();
		batch3[0].arguments = new M3Argument();
		batch3[0].arguments.db = "jcvi";
		batch3[0].arguments.subject = "GO:0003887";
		
		M3BatchResponse response3 = handler.m3Batch(uid, intention, batch3);
		assertEquals(uid, response3.uid);
		assertEquals(intention, response3.intention);
		assertEquals(response3.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, response3.message_type);
		final String modelId3 = (String) response3.data.get("id");
		
		assertNotEquals(modelId1, modelId3);
		assertNotEquals(modelId2, modelId3);
	}
	
	@Test
	public void testDelete() throws Exception {
		models.dispose();
		
		final String modelId = generateBlankModel();
		
		// create
		M3Request[] batch1 = new M3Request[1];
		batch1[0] = new M3Request();
		batch1[0].entity = Entity.individual.name();
		batch1[0].operation = Operation.create.getLbl();
		batch1[0].arguments = new M3Argument();
		batch1[0].arguments.modelId = modelId;
		batch1[0].arguments.subject = "GO:0008104"; // protein localization
		batch1[0].arguments.expressions = new M3Expression[2];
		batch1[0].arguments.expressions[0] = new M3Expression();
		batch1[0].arguments.expressions[0].type = "svf";
		batch1[0].arguments.expressions[0].onProp = "RO:0002333"; // enabled_by
		batch1[0].arguments.expressions[0].literal = "MGI:MGI:00000";
		
		batch1[0].arguments.expressions[1] = new M3Expression();
		batch1[0].arguments.expressions[1].type = "svf";
		batch1[0].arguments.expressions[1].onProp = "BFO:0000050"; // part_of
		batch1[0].arguments.expressions[1].literal = "happiness";
		
		M3BatchResponse response1 = handler.m3Batch(uid, intention, batch1);
		assertEquals(uid, response1.uid);
		assertEquals(intention, response1.intention);
		assertEquals(response1.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, response1.message_type);
		
		
		List<Map<Object, Object>> iObjs1 = (List) response1.data.get(KEY_INDIVIDUALS);
		assertEquals(1, iObjs1.size());
		Map<Object, Object> individual1 = iObjs1.get(0);
		assertNotNull(individual1);
		final String individualId = (String) individual1.get(MolecularModelJsonRenderer.KEY.id);
		assertNotNull(individualId);
		
		List<Map<Object, Object>> types1 = (List) individual1.get(MolecularModelJsonRenderer.KEY.type);
		assertEquals(3, types1.size());
		String happinessId = null;
		for(Map<Object, Object> e : types1) {
			Object cType = e.get(MolecularModelJsonRenderer.KEY.type);
			if (MolecularModelJsonRenderer.VAL.Restriction.equals(cType)) {
				Map<Object, Object> svf = (Map<Object, Object>) e.get(MolecularModelJsonRenderer.KEY.someValuesFrom);
				String id = (String) svf.get(MolecularModelJsonRenderer.KEY.id);
				if (id.contains("happ")) {
					happinessId = id;
					break;
				}
			}
		}
		assertNotNull(happinessId);
		
		// delete
		M3Request[] batch2 = new M3Request[1];
		batch2[0] = new M3Request();
		batch2[0].entity = Entity.individual.name();
		batch2[0].operation = Operation.removeType.getLbl();
		batch2[0].arguments = new M3Argument();
		batch2[0].arguments.modelId = modelId;
		batch2[0].arguments.individual = individualId;
		
		batch2[0].arguments.expressions = new M3Expression[1];
		batch2[0].arguments.expressions[0] = new M3Expression();
		batch2[0].arguments.expressions[0].type = "svf";
		batch2[0].arguments.expressions[0].onProp = "BFO:0000050"; // part_of
		batch2[0].arguments.expressions[0].literal = happinessId;
		
		
		M3BatchResponse response2 = handler.m3Batch(uid, intention, batch2);
		assertEquals(uid, response2.uid);
		assertEquals(intention, response2.intention);
		assertEquals(response2.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, response2.message_type);
		
		List<Map<Object, Object>> iObjs2 = (List) response2.data.get(KEY_INDIVIDUALS);
		assertEquals(1, iObjs2.size());
		Map<Object, Object> individual2 = iObjs2.get(0);
		assertNotNull(individual2);
		List<Map<Object, Object>> types2 = (List) individual2.get(MolecularModelJsonRenderer.KEY.type);
		assertEquals(2, types2.size());
	}
	
	@Test
	public void testModelSearch() throws Exception {
		models.setPathToOWLFiles(folder.newFolder().getCanonicalPath());
		models.dispose();

		final String modelId = generateBlankModel();
		
		// create
		M3Request[] batch1 = new M3Request[1];
		batch1[0] = new M3Request();
		batch1[0].entity = Entity.individual.name();
		batch1[0].operation = Operation.create.getLbl();
		batch1[0].arguments = new M3Argument();
		batch1[0].arguments.modelId = modelId;
		batch1[0].arguments.subject = "GO:0008104"; // protein localization
		batch1[0].arguments.expressions = new M3Expression[2];
		batch1[0].arguments.expressions[0] = new M3Expression();
		batch1[0].arguments.expressions[0].type = "svf";
		batch1[0].arguments.expressions[0].onProp = "RO:0002333"; // enabled_by
		batch1[0].arguments.expressions[0].literal = "MGI:MGI:00000";
		
		batch1[0].arguments.expressions[1] = new M3Expression();
		batch1[0].arguments.expressions[1].type = "svf";
		batch1[0].arguments.expressions[1].onProp = "BFO:0000050"; // part_of
		batch1[0].arguments.expressions[1].literal = "happiness";
		
		M3BatchResponse response1 = handler.m3Batch(uid, intention, batch1);
		assertEquals(uid, response1.uid);
		assertEquals(intention, response1.intention);
		assertEquals(response1.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, response1.message_type);
	
		// search
		M3Request[] batch2 = new M3Request[1];
		batch2[0] = new M3Request();
		batch2[0].entity = Entity.model.name();
		batch2[0].operation = Operation.search.getLbl();
		batch2[0].arguments = new M3Argument();
		batch2[0].arguments.values = new M3Pair[1];
		batch2[0].arguments.values[0] = new M3Pair();
		batch2[0].arguments.values[0].key = "id";
		batch2[0].arguments.values[0].value = "GO:0008104";
		
		M3BatchResponse response2 = handler.m3Batch(uid, intention, batch2);
		assertEquals(uid, response2.uid);
		assertEquals(intention, response2.intention);
		assertEquals(response2.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, response2.message_type);
		
		Set<String> foundIds = (Set<String>) response2.data.get("model_ids");
		assertEquals(1, foundIds.size());
		assertTrue(foundIds.contains(modelId));
	}
	
	@Test
	public void testCreateModelAndIndividualBatch() throws Exception {
		M3Request[] batch = new M3Request[2];
		batch[0] = new M3Request();
		batch[0].entity = Entity.model.name();
		batch[0].operation = Operation.generateBlank.getLbl();
		batch[1] = new M3Request();
		batch[1].entity = Entity.individual.name();
		batch[1].operation = Operation.createComposite.getLbl();
		batch[1].arguments = new M3Argument();
		batch[1].arguments.subject = "GO:0003674"; // molecular function
		batch[1].arguments.predicate = "BFO:0000050"; // part of
		batch[1].arguments.object = "GO:0008150"; // biological process
		batch[1].arguments.expressions = new M3Expression[1];
		batch[1].arguments.expressions[0] = new M3Expression();
		batch[1].arguments.expressions[0].type = "svf";
		batch[1].arguments.expressions[0].onProp = "RO:0002333"; // enabled_by
		batch[1].arguments.expressions[0].literal = "UniProtKB:P00000";
		
		M3BatchResponse response = handler.m3Batch(uid, intention, batch);
		assertEquals(uid, response.uid);
		assertEquals(intention, response.intention);
		assertEquals(response.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, response.message_type);
	}
	
	@Test
	public void testInconsistentModel() throws Exception {
		models.dispose();
		
		final String modelId = generateBlankModel();
		
		// create
		M3Request[] batch1 = new M3Request[1];
		batch1[0] = new M3Request();
		batch1[0].entity = Entity.individual.name();
		batch1[0].operation = Operation.create.getLbl();
		batch1[0].arguments = new M3Argument();
		batch1[0].arguments.modelId = modelId;
		batch1[0].arguments.subject = "GO:0009653"; // anatomical structure morphogenesis
		batch1[0].arguments.expressions = new M3Expression[1];
		batch1[0].arguments.expressions[0] = new M3Expression();
		batch1[0].arguments.expressions[0].type = "class";
		batch1[0].arguments.expressions[0].literal = "GO:0048856"; // anatomical structure development
		
		M3BatchResponse response1 = handler.m3Batch(uid, intention, batch1);
		assertEquals(uid, response1.uid);
		assertEquals(intention, response1.intention);
		assertEquals(response1.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, response1.message_type);
		Map<Object, Object> data = response1.data;
		Object inconsistentFlag = data.get("inconsistent_p");
		assertEquals(Boolean.TRUE, inconsistentFlag);
	}

	@Test
	public void testInferences() throws Exception {
		models.dispose();
		
		final String modelId = generateBlankModel();
		
		// GO:0009826 ! unidimensional cell growth
		// GO:0000902 ! cell morphogenesis
		// should infer only one type: 'unidimensional cell growth'
		// 'cell morphogenesis' is a super-class and redundant
		
		// create
		M3Request[] batch1 = new M3Request[1];
		batch1[0] = new M3Request();
		batch1[0].entity = Entity.individual.name();
		batch1[0].operation = Operation.create.getLbl();
		batch1[0].arguments = new M3Argument();
		batch1[0].arguments.modelId = modelId;
		batch1[0].arguments.subject = "GO:0000902"; // cell morphogenesis
		batch1[0].arguments.expressions = new M3Expression[1];
		batch1[0].arguments.expressions[0] = new M3Expression();
		batch1[0].arguments.expressions[0].type = "class";
		batch1[0].arguments.expressions[0].literal = "GO:0009826"; // unidimensional cell growth

		M3BatchResponse response1 = handler.m3Batch(uid, intention, batch1);
		assertEquals(uid, response1.uid);
		assertEquals(intention, response1.intention);
		assertEquals(response1.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, response1.message_type);
		Map<Object, Object> data = response1.data;
		assertNull("Model should not be inconsistent", data.get("inconsistent_p"));
		List inferred = (List) data.get(KEY_INDIVIDUALS_INFERENCES);
		assertNotNull(inferred);
		assertEquals(1, inferred.size());
		Map inferredData = (Map) inferred.get(0);
		List types = (List) inferredData.get(KEY.type);
		assertEquals(1, types.size());
		Map type = (Map) types.get(0);
		assertEquals("GO:0009826", type.get(KEY.id));
	}
	
	/**
	 * @return modelId
	 */
	private String generateBlankModel() {
		// create blank model
		M3Request[] batch = new M3Request[1];
		batch[0] = new M3Request();
		batch[0].entity = Entity.model.name();
		batch[0].operation = Operation.generateBlank.getLbl();
		M3BatchResponse resp1 = handler.m3Batch(uid, intention, batch);
		assertEquals(resp1.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, resp1.message_type);
		String modelId = (String) resp1.data.get("id");
		assertNotNull(modelId);
		return modelId;
	}
	
	static void printJson(Object resp) {
		GsonBuilder builder = new GsonBuilder();
		builder.setPrettyPrinting();
		Gson gson = builder.create();
		String json = gson.toJson(resp);
		System.out.println("---------");
		System.out.println(json);
		System.out.println("---------");
	}
}
