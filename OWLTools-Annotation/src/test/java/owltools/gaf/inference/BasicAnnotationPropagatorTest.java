package owltools.gaf.inference;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;

import org.junit.Test;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLObjectProperty;

import owltools.OWLToolsTestBasics;
import owltools.gaf.GafDocument;
import owltools.gaf.GafObjectsBuilder;
import owltools.graph.OWLGraphEdge;
import owltools.graph.OWLGraphWrapper;
import owltools.graph.OWLQuantifiedProperty;
import owltools.io.ParserWrapper;

public class BasicAnnotationPropagatorTest extends OWLToolsTestBasics {

	@Test
	public void testClosure() throws Exception {
		ParserWrapper pw = new ParserWrapper();
		OWLGraphWrapper g = pw.parseToOWLGraph(getResourceIRIString("lmajor_f2p_test_go_subset.obo"));
		
		OWLClass c = g.getOWLClassByIdentifier("GO:0004004"); // ATP-dependent RNA helicase activity
		
		boolean found = false;
		
		Set<OWLGraphEdge> closure = g.getOutgoingEdgesClosure(c);
		for (OWLGraphEdge edge : closure) {
			List<OWLQuantifiedProperty> propertyList = edge.getQuantifiedPropertyList();
			if (propertyList.size() == 1) {
				OWLQuantifiedProperty quantifiedProperty = propertyList.get(0);
				final OWLObjectProperty property = quantifiedProperty.getProperty();
				String property_id = g.getIdentifier(property);
				if ("part_of".equals(property_id)) {
					OWLObject target = edge.getTarget();
					if (target instanceof OWLClass) {
						String targetId = g.getIdentifier(target);
						if ("GO:0006200".equals(targetId)) {
							found = true;
						}
					}
				}
			}
		}
		
		assertTrue(found);
	}
	
	@Test
	public void test() throws Exception {
		ParserWrapper pw = new ParserWrapper();
		OWLGraphWrapper g = pw.parseToOWLGraph(getResourceIRIString("lmajor_f2p_test_go_subset.obo"));
		GafObjectsBuilder b = new GafObjectsBuilder();
		GafDocument gafDocument = b.buildDocument(getResource("lmajor_f2p_test.gaf"));
		
		BasicAnnotationPropagator propagator = new BasicAnnotationPropagator(gafDocument, g);
		
		Set<Prediction> allPredictions = propagator.getAllPredictions();
		assertEquals(1, allPredictions.size());
	}

}
