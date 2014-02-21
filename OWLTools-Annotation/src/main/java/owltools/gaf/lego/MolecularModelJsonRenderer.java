package owltools.gaf.lego;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLIndividualAxiom;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLNamedObject;
import org.semanticweb.owlapi.model.OWLObjectIntersectionOf;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLObjectUnionOf;
import org.semanticweb.owlapi.model.OWLOntology;

import owltools.graph.OWLGraphWrapper;
import owltools.vocab.OBOUpperVocabulary;

import com.google.gson.Gson;

/**
 * A Renderer that takes a MolecularModel (an OWL ABox) and generates Map objects
 * that can be translated to JSON using Gson.
 * 
 * TODO - make this inherit from a generic renderer - use OWLAPI visitor?
 * TODO - abstract some of this into a generic OWL to JSON-LD converter
 * TODO - include inferred types
 * 
 * @author cjm
 *
 */
public class MolecularModelJsonRenderer {
	
	private static Logger LOG = Logger.getLogger(MolecularModelJsonRenderer.class);
	
	OWLGraphWrapper graph;

	/**
	 * JSON-LD keywords for elements of different vocabularies:
	 * 
	 *<li>RDF
	 *<li>OWL
	 *<li>GO
	 *<li>RO
	 *
	 * TODO: use a complex enum to generate IRIs for each
	 *
	 */
	public enum KEY {
		id,
		label,
		type,
		enabledBy,
		occursIn,
		
		onProperty,
		someValuesFrom,
		
		intersectionOf,
		unionOf,
		
		subject,
		property,
		object
	}
	
	/**
	 * merge with KEY?
	 *
	 */
	public enum VAL {
		Restriction,
		someValueFrom
	}
	
	Gson gson = new Gson();
	
	/**
	 * @param ontology
	 */
	public MolecularModelJsonRenderer(OWLOntology ontology) {
		this(new OWLGraphWrapper(ontology));
	}
	
	/**
	 * @param graph
	 */
	public MolecularModelJsonRenderer(OWLGraphWrapper graph) {
		super();
		this.graph = graph;
	}

	/**
	 * @param ont
	 * @return Map to be passed to Gson
	 */
	public Map<Object, Object> renderObject(OWLOntology ont) {
		Map<Object, Object> model = new HashMap<Object, Object>();
		
		// per-Individual
		List<Map<Object, Object>> iObjs = new ArrayList<Map<Object, Object>>();
		for (OWLNamedIndividual i : ont.getIndividualsInSignature()) {
			iObjs.add(renderObject(ont, i));
		}
		model.put("individuals", iObjs);
		
		// per-Assertion
		Set<OWLObjectProperty> usedProps = new HashSet<OWLObjectProperty>();
		
		List<Map<Object, Object>> aObjs = new ArrayList<Map<Object, Object>>();
		for (OWLObjectPropertyAssertionAxiom opa : 
			ont.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION)) {
			aObjs.add(renderObject(ont, opa));
			usedProps.addAll(opa.getObjectPropertiesInSignature());
		}
		model.put("facts", aObjs);

		// per-Property
		List<Map<Object, Object>> pObjs = new ArrayList<Map<Object, Object>>();
		for (OWLObjectProperty p : ont.getObjectPropertiesInSignature(true)) {
			if (usedProps.contains(p)) {
				pObjs.add(renderObject(ont, p));
			}
		}
		model.put("properties", pObjs);

		return model;
		
	}
	
	public Map<Object, Object> renderIndividuals(Collection<OWLNamedIndividual> individuals) {
		OWLOntology ont = graph.getSourceOntology();
		Map<Object, Object> map = new HashMap<Object, Object>();
		List<Map<Object, Object>> iObjs = new ArrayList<Map<Object, Object>>();
		List<OWLNamedIndividual> individualIds = new ArrayList<OWLNamedIndividual>();
		Set<OWLObjectPropertyAssertionAxiom> opAxioms = new HashSet<OWLObjectPropertyAssertionAxiom>();
		for (OWLIndividual i : individuals) {
			if (i instanceof OWLNamedIndividual) {
				OWLNamedIndividual named = (OWLNamedIndividual)i;
				iObjs.add(renderObject(ont, named));
				individualIds.add(named);
				
				Set<OWLIndividualAxiom> iAxioms = ont.getAxioms(i);
				for (OWLIndividualAxiom owlIndividualAxiom : iAxioms) {
					if (owlIndividualAxiom instanceof OWLObjectPropertyAssertionAxiom) {
						opAxioms.add((OWLObjectPropertyAssertionAxiom) owlIndividualAxiom);
					}
				}
			}
		}
		map.put("individuals", iObjs);
		
		List<Map<Object, Object>> aObjs = new ArrayList<Map<Object, Object>>();
		for (OWLObjectPropertyAssertionAxiom opa : opAxioms) {
			aObjs.add(renderObject(ont, opa));
		}
		map.put("facts", aObjs);
		
		// TODO decide on properties
		
		return map;
	}
	
	/**
	 * @param ont
	 * @param i
	 * @return Map to be passed to Gson
	 */
	public Map<Object, Object> renderObject(OWLOntology ont, OWLNamedIndividual i) {
		Map<Object, Object> iObj = new HashMap<Object, Object>();
		iObj.put(KEY.id, getId(i, graph));
		iObj.put(KEY.label, getLabel(i));
		
		List<Object> typeObjs = new ArrayList<Object>();		
		for (OWLClassExpression x : i.getTypes(ont)) {
			typeObjs.add(renderObject(ont, x));
		}
		Map<OWLObjectPropertyExpression, Set<OWLIndividual>> pvs = i.getObjectPropertyValues(ont);
		for (OWLObjectPropertyExpression p : pvs.keySet()) {
			List<Object> valObjs = new ArrayList<Object>();
			for (OWLIndividual v : pvs.get(p)) {
				valObjs.add(getAtom((OWLNamedObject) v));
			}
			iObj.put(getId((OWLNamedObject) p, graph), valObjs);
		}
		iObj.put(KEY.type, typeObjs);
		return iObj;
	}
	
	/**
	 * @param ont
	 * @param opa
	 * @return Map to be passed to Gson
	 */
	public Map<Object, Object> renderObject(OWLOntology ont, OWLObjectPropertyAssertionAxiom opa) {
		Map<Object, Object> aObj = new HashMap<Object, Object>();
		OWLNamedIndividual subject;
		OWLObjectProperty property;
		OWLNamedIndividual object;

		subject = (OWLNamedIndividual) opa.getSubject();
		property = (OWLObjectProperty) opa.getProperty();
		object = (OWLNamedIndividual) opa.getObject();

		aObj.put(KEY.subject, getId(subject, graph));
		aObj.put(KEY.property, getId(property, graph));
		aObj.put(KEY.object, getId(object, graph));
		
		return aObj;
	}

	public Map<Object, Object> renderObject(OWLOntology ont, OWLObjectProperty p) {
		Map<Object, Object> iObj = new HashMap<Object, Object>();
		iObj.put(KEY.id, getId(p, graph));
		iObj.put(KEY.label, getLabel(p));
		
		iObj.put(KEY.type, "ObjectProperty");
		return iObj;
	}
	/**
	 * @param ont
	 * @param x
	 * @return  Object to be passed to Gson
	 */
	private Object renderObject(OWLOntology ont, OWLClassExpression x) {
		Map<Object, Object> xObj = new HashMap<Object, Object>();
		if (x.isAnonymous()) {
			if (x instanceof OWLObjectIntersectionOf) {
				List<Object> yObjs = new ArrayList<Object>();		
				for (OWLClassExpression y : ((OWLObjectIntersectionOf)x).getOperands()) {
					yObjs.add(renderObject(ont, y));
				}
				xObj.put(KEY.intersectionOf, yObjs);
			}
			else if (x instanceof OWLObjectUnionOf) {
				List<Object> yObjs = new ArrayList<Object>();		
				for (OWLClassExpression y : ((OWLObjectUnionOf)x).getOperands()) {
					yObjs.add(renderObject(ont, y));
				}
				xObj.put(KEY.unionOf, yObjs);
			}
			else if (x instanceof OWLObjectSomeValuesFrom) {
				OWLObjectSomeValuesFrom svf = (OWLObjectSomeValuesFrom)x;
				xObj.put(KEY.type, VAL.Restriction);
				xObj.put(KEY.onProperty, renderObject(ont, svf.getProperty()));
				xObj.put(KEY.someValuesFrom, renderObject(ont, svf.getFiller()));				
			}
			else {
				// TODO
			}
		}
		else {
			return getAtom((OWLNamedObject)x);
		}
		return xObj;
	}

	private Object renderObject(OWLOntology ont,
			OWLObjectPropertyExpression px) {
		if (px.isAnonymous()) {
			return null; // TODO
		}
		else {
			return getAtom((OWLNamedObject)px);
		}
	}

	private Object getLabel(OWLNamedObject i) {
		return graph.getLabel(i);
	}
	
	private Object getAtom(OWLNamedObject i) {
		Map<Object, Object> xObj = new HashMap<Object, Object>();
		
		if (LOG.isDebugEnabled()) {
			LOG.debug("atom: "+i+" "+getId(i, graph));
		}
		xObj.put(KEY.id, getId(i, graph));
		xObj.put(KEY.label, getLabel(i));
		String type = null;
		if (i instanceof OWLNamedIndividual) {
			type = "NamedIndividual";
		}
		else if (i instanceof OWLClass) {
			type = "Class";
		}
		else if (i instanceof OWLObjectProperty) {
			type = "ObjectProperty";
		}
		xObj.put(KEY.type, type);
		return xObj;
	}


	/**
	 * @param i
	 * @param graph 
	 * @return id
	 * 
	 * @see MolecularModelJsonRenderer#getIRI
	 */
	public static String getId(OWLNamedObject i, OWLGraphWrapper graph) {
		if (i instanceof OWLObjectProperty) {
			String relId = graph.getIdentifier(i);
			return relId;
		}
		IRI iri = i.getIRI();
		return getId(iri);
	}

	/**
	 * @param iri
	 * @return id
	 */
	public static String getId(IRI iri) {
		String iriString = iri.toString();
		// remove obo prefix from IRI
		String full = StringUtils.removeStart(iriString, OBOUpperVocabulary.OBO);
		// replace first '_' char with ':' char
		String replaced = StringUtils.replaceOnce(full, "_", ":");
		return replaced;
	}
	
	/**
	 * Inverse method to {@link #getId}
	 * 
	 * @param id
	 * @param graph
	 * @return IRI
	 * 
	 * @see MolecularModelJsonRenderer#getId
	 */
	public static IRI getIRI(String id, OWLGraphWrapper graph) {
		if (id.indexOf(':') < 0) {
			return graph.getIRIByIdentifier(id);
		}
		if(id.startsWith(OBOUpperVocabulary.OBO) ){
			return IRI.create(id);
		}
		String fullIRI = OBOUpperVocabulary.OBO + StringUtils.replaceOnce(id, ":", "_");
		return IRI.create(fullIRI);
	}
	
	public String renderJson(OWLOntology ont) {
		Map<Object, Object> obj = renderObject(ont);
		return gson.toJson(obj);
	}

}
