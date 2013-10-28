package owltools.sim2;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import org.apache.log4j.Logger;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.impl.DefaultNode;
import org.semanticweb.owlapi.reasoner.impl.OWLClassNode;

import com.googlecode.javaewah.EWAHCompressedBitmap;

import owltools.sim.io.SimResultRenderer.AttributesSimScores;
import owltools.sim2.SimpleOwlSim.Direction;
import owltools.sim2.SimpleOwlSim.Metric;
import owltools.sim2.SimpleOwlSim.ScoreAttributePair;
import owltools.sim2.SimpleOwlSim.ScoreAttributesPair;
import owltools.util.ClassExpressionPair;

/**
 * Faster implementation of OwlSim
 * 
 * Makes use of integers to index classes, and bitmaps to represent class sets
 * 
 * @author cjm
 *
 */
public class FastOwlSim extends AbstractOwlSim implements OwlSim {
	
	private Logger LOG = Logger.getLogger(FastOwlSim.class);

	private Map<OWLNamedIndividual, Set<OWLClass>> elementToDirectAttributesMap;
	private Map<OWLNamedIndividual, Set<Node<OWLClass>>> elementToInferredAttributesMap; // REDUNDANT WITH typesMap

	private Map<OWLClass,Set<Node<OWLClass>>> superclassMap; // cache of RSub(c)->Cs
	private Map<OWLNamedIndividual,Set<Node<OWLClass>>> typesMap; // cache of Type(i)->Cs

	private Map<OWLClass,Set<Integer>> superclassIntMap; // cache of RSub(c)->Ints
	private Map<OWLNamedIndividual,Set<Integer>> typesIntMap; // cache of RSub(c)->Ints

	private Map<OWLClass,EWAHCompressedBitmap> superclassBitmapMap; // cache of RSub(c)->BM
	private Map<OWLNamedIndividual, EWAHCompressedBitmap> typesBitmapMap; // cache of Type(i)->BM

	private Map<OWLClass,Integer> classIndex;
	private OWLClass[] classArray;

	private Set<OWLClass> allTypes = null; // all Types used in Type(e) for all e in E

	private Map<OWLClass, Double> icCache;

	private Map<ClassIntPair, Set<Integer>> classPairLCSMap;

	private class ClassIntPair {
		int c;
		int d;
		public ClassIntPair(int c, int d) {
			super();
			this.c = c;
			this.d = d;
		}

		@Override
		public int hashCode() {
			final int prime = 991;
			int result = 1;
			result = prime * result + c;
			result = prime * result + d;
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			ClassIntPair other = (ClassIntPair) obj;
			return c == other.c && d == other.d;
		}
	}



	public FastOwlSim(OWLOntology sourceOntology) {
		reasoner = new ElkReasonerFactory().createReasoner(sourceOntology);
	}

	public FastOwlSim(OWLReasoner reasoner) {
		this.reasoner = reasoner;
	}

	@Override
	public Set<OWLClass> getAllAttributeClasses() {
		return allTypes;
	}

	@Override
	public void createElementAttributeMapFromOntology() throws UnknownOWLClassException {
		Set<OWLClass> cset = getSourceOntology().getClassesInSignature();
		Set<OWLNamedIndividual> inds = getSourceOntology().getIndividualsInSignature(true);
		LOG.info("Cset size="+cset.size());
		LOG.info("Inds size="+inds.size());
		
		// cache E -> Type(E)
		elementToDirectAttributesMap = new HashMap<OWLNamedIndividual,Set<OWLClass>>();
		elementToInferredAttributesMap = new HashMap<OWLNamedIndividual,Set<Node<OWLClass>>>();
		allTypes = new HashSet<OWLClass>();
		for (OWLNamedIndividual e : inds) {

			// The attribute classes for an individual are the direct inferred
			// named types. We assume that grouping classes have already been
			// generated.
			NodeSet<OWLClass> nodeset = getReasoner().getTypes(e, true);
			allTypes.addAll(nodeset.getFlattened());
			elementToInferredAttributesMap.put(e, nodeset.getNodes());
			elementToDirectAttributesMap.put(e, 
					getReasoner().getTypes(e, false).getFlattened());
		}

		// Create a bidirectional index, class by number
		int n=0;
		classArray = (OWLClass[]) Array.newInstance(OWLClass.class, cset.size());
		classIndex = new HashMap<OWLClass,Integer>();

		// TODO - investigate if ordering elements makes a difference;
		// e.g. if more frequent classes recieve lower bit indices this
		// may speed certain BitMap operations?
		for (OWLClass c : cset) {
			classArray[n] = c;
			classIndex.put(c, n);
			n++;
		}

		for (OWLClass c : cset) {
			ancsCachedModifiable(c);
			ancsIntsCachedModifiable(c);
			ancsBitmapCachedModifiable(c);
		}
	}

	private EWAHCompressedBitmap convertIntsToBitmap(Set<Integer> bits) {
		EWAHCompressedBitmap bm = new EWAHCompressedBitmap();
		ArrayList<Integer> bitlist = new ArrayList<Integer>(bits);
		Collections.sort(bitlist);
		for (Integer i : bitlist) {
			bm.set(i.intValue());
		}
		return bm;
	}

	private EWAHCompressedBitmap ancsBitmapCachedModifiable(OWLClass c) throws UnknownOWLClassException {
		if (superclassBitmapMap != null && superclassBitmapMap.containsKey(c)) {
			return superclassBitmapMap.get(c);
		}
		Set<Integer> caints = ancsIntsCachedModifiable(c);
		EWAHCompressedBitmap bm = convertIntsToBitmap(caints);
		if (superclassBitmapMap == null)
			superclassBitmapMap = new HashMap<OWLClass,EWAHCompressedBitmap>();
		superclassBitmapMap.put(c, bm);
		return bm;		
	}

	private EWAHCompressedBitmap ancsBitmapCachedModifiable(OWLNamedIndividual i) throws UnknownOWLClassException {
		if (typesBitmapMap != null && typesBitmapMap.containsKey(i)) {
			return typesBitmapMap.get(i);
		}
		Set<Integer> caints = ancsIntsCachedModifiable(i);
		EWAHCompressedBitmap bm = convertIntsToBitmap(caints);
		if (typesBitmapMap == null)
			typesBitmapMap = new HashMap<OWLNamedIndividual,EWAHCompressedBitmap>();
		typesBitmapMap.put(i, bm);
		return bm;		
	}


	private Set<Integer> ancsIntsCachedModifiable(OWLClass c) throws UnknownOWLClassException {
		if (superclassIntMap != null && superclassIntMap.containsKey(c)) {
			return superclassIntMap.get(c);
		}
		Set<Integer> a = ancsInts(c);
		if (superclassIntMap == null)
			superclassIntMap = new HashMap<OWLClass,Set<Integer>>();
		superclassIntMap.put(c, a);
		return a;
	}	

	private Set<Integer> ancsIntsCachedModifiable(OWLNamedIndividual i) throws UnknownOWLClassException {
		if (typesIntMap != null && typesIntMap.containsKey(i)) {
			return typesIntMap.get(i);
		}
		Set<Integer> a = ancsInts(i);
		if (typesIntMap == null)
			typesIntMap = new HashMap<OWLNamedIndividual,Set<Integer>>();
		typesIntMap.put(i, a);
		return a;
	}	

	private Set<Integer> ancsInts(OWLClass c) throws UnknownOWLClassException {
		Set<Node<OWLClass>> ancs = ancsCachedModifiable(c);
		Set<Integer> ancsInts = new HashSet<Integer>();
		OWLClass thing = owlThing();
		for (Node<OWLClass> anc : ancs) {
			// TODO - verify robust for non-Rep elements
			OWLClass ac = anc.getRepresentativeElement();
			if (ac.equals(thing))
				continue;
			Integer ix = classIndex.get(ac);
			if (ix == null) {
				throw new UnknownOWLClassException(ac);
			}
			ancsInts.add(ix.intValue());
		}
		return ancsInts;
	}

	private Set<Integer> ancsInts(OWLNamedIndividual i) throws UnknownOWLClassException {
		Set<Node<OWLClass>> ancs = ancsCachedModifiable(i);
		Set<Integer> ancsInts = new HashSet<Integer>();
		OWLClass thing = owlThing();
		for (Node<OWLClass> anc : ancs) {
			// TODO - verify robust for non-Rep elements
			OWLClass ac = anc.getRepresentativeElement();
			if (ac.equals(thing))
				continue;
			Integer ix = classIndex.get(ac);
			if (ix == null) {
				throw new UnknownOWLClassException(ac);
			}
			ancsInts.add(ix.intValue());
		}
		return ancsInts;
	}

	@Deprecated
	private Set<Node<OWLClass>> ancsCached(OWLClass c) {
		return new HashSet<Node<OWLClass>>(ancsCachedModifiable(c));
	}

	private Set<Node<OWLClass>> ancsCachedModifiable(OWLClass c) {
		if (superclassMap != null && superclassMap.containsKey(c)) {
			return superclassMap.get(c);
		}
		Set<Node<OWLClass>> a = ancs(c);
		if (superclassMap == null)
			superclassMap = new HashMap<OWLClass,Set<Node<OWLClass>>>();
		superclassMap.put(c, a);
		return a;
	}	

	private Set<Node<OWLClass>> ancsCachedModifiable(OWLNamedIndividual i) {
		if (typesMap != null && typesMap.containsKey(i)) {
			return typesMap.get(i);
		}
		Set<Node<OWLClass>> a = ancs(i);
		if (typesMap == null)
			typesMap = new HashMap<OWLNamedIndividual,Set<Node<OWLClass>>>();
		typesMap.put(i, a);
		return a;
	}	

	private Set<Node<OWLClass>> ancs(OWLClass c) {
		NodeSet<OWLClass> ancs = getReasoner().getSuperClasses(c, false);
		Set<Node<OWLClass>> nodes = ancs.getNodes();
		nodes.add(getReasoner().getEquivalentClasses(c));
		nodes.remove(owlThingNode());
		return nodes;
	}
	private Set<Node<OWLClass>> ancs(OWLNamedIndividual i) {		
		Set<Node<OWLClass>> nodes = getReasoner().getTypes(i, false).getNodes();
		nodes.remove(owlThingNode());
		return nodes;
	}


	@Override
	public Set<OWLClass> getAttributesForElement(OWLNamedIndividual e) throws UnknownOWLClassException {
		if (elementToDirectAttributesMap == null)
			createElementAttributeMapFromOntology();
		return new HashSet<OWLClass>(elementToDirectAttributesMap.get(e));
	}

	@Override
	public Set<OWLNamedIndividual> getElementsForAttribute(OWLClass c) {
		return getReasoner().getInstances(c, false).getFlattened();
	}

	@Override
	public int getNumElementsForAttribute(OWLClass c) {
		return getElementsForAttribute(c).size();
	}

	@Override
	public Set<OWLNamedIndividual> getAllElements() {
		// Note: will only return elements that have >=1 attributes
		return elementToDirectAttributesMap.keySet();
	}


	@Override
	public Double getInformationContentForAttribute(OWLClass c) {
		if (icCache.containsKey(c)) return icCache.get(c);
		int freq = getNumElementsForAttribute(c);
		Double ic = null;
		if (freq > 0) {
			ic = -Math.log(((double) (freq) / getCorpusSize())) / Math.log(2);
		}
		icCache.put(c, ic);
		return ic;
	}


	@Override
	public Set<Node<OWLClass>> getInferredAttributes(OWLNamedIndividual a) {
		return new HashSet<Node<OWLClass>>(elementToInferredAttributesMap.get(a));
	}

	@Override
	public Set<Node<OWLClass>> getNamedReflexiveSubsumers(OWLClass a) {
		return ancs(a);
	}

	@Override
	public Set<Node<OWLClass>> getNamedCommonSubsumers(OWLClass c, OWLClass d) throws UnknownOWLClassException {
		EWAHCompressedBitmap bmc = ancsBitmapCachedModifiable(c);
		EWAHCompressedBitmap bmd = ancsBitmapCachedModifiable(d);
		EWAHCompressedBitmap cad = bmc.and(bmd);
		Set<Node<OWLClass>> nodes = new HashSet<Node<OWLClass>>();
		for (int ix : cad.toArray()) {
			OWLClassNode node = new OWLClassNode(classArray[ix]);
			nodes.add(node);
		}
		return nodes;
	}

	@Override
	public int getNamedCommonSubsumersCount(OWLClass c, OWLClass d) throws UnknownOWLClassException {
		EWAHCompressedBitmap bmc = ancsBitmapCachedModifiable(c);
		EWAHCompressedBitmap bmd = ancsBitmapCachedModifiable(d);
		return bmc.andCardinality(bmd);
	}

	@Override
	public Set<Node<OWLClass>> getNamedCommonSubsumers(OWLNamedIndividual i,
			OWLNamedIndividual j) throws UnknownOWLClassException {
		EWAHCompressedBitmap bmc = ancsBitmapCachedModifiable(i);
		EWAHCompressedBitmap bmd = ancsBitmapCachedModifiable(j);
		EWAHCompressedBitmap cad = bmc.and(bmd);
		Set<Node<OWLClass>> nodes = new HashSet<Node<OWLClass>>();
		for (int ix : cad.toArray()) {
			OWLClassNode node = new OWLClassNode(classArray[ix]);
			nodes.add(node);
		}
		return nodes;
	}

	@Override
	public Set<Node<OWLClass>> getNamedLowestCommonSubsumers(OWLClass a,
			OWLClass b) throws UnknownOWLClassException {
		// currently no need to cache this, as only called from
		// getLowestCommonSubsumerIC, which does its own caching
		Set<Node<OWLClass>> commonSubsumerNodes = getNamedCommonSubsumers(a, b);
		Set<Node<OWLClass>> rNodes = new HashSet<Node<OWLClass>>();

		// remove redundant
		for (Node<OWLClass> node : commonSubsumerNodes) {
			rNodes.addAll(getReasoner().getSuperClasses(
					node.getRepresentativeElement(), false).getNodes());
		}
		commonSubsumerNodes.removeAll(rNodes);
		return commonSubsumerNodes;
	}

	@Override
	public double getAttributeSimilarity(OWLClass c, OWLClass d, Metric metric) throws UnknownOWLClassException {
		if (metric.equals(Metric.JACCARD)) {
			return getAttributeJaccardSimilarity(c, d);
		} else if (metric.equals(Metric.OVERLAP)) {
			return getNamedCommonSubsumers(c, d).size();
		} else if (metric.equals(Metric.NORMALIZED_OVERLAP)) {
			return getNamedCommonSubsumers(c, d).size()
					/ Math.min(getNamedReflexiveSubsumers(c).size(),
							getNamedReflexiveSubsumers(d).size());
		} else if (metric.equals(Metric.DICE)) {
			// TODO
			return -1;
		} else {
			return 0;
		}
	}

	@Override
	public double getAttributeJaccardSimilarity(OWLClass c, OWLClass d) throws UnknownOWLClassException {
		EWAHCompressedBitmap bmc = ancsBitmapCachedModifiable(c);
		EWAHCompressedBitmap bmd = ancsBitmapCachedModifiable(d);

		return bmc.andCardinality(bmd) / (double) bmc.orCardinality(bmd);
	}

	@Override
	public double getElementJaccardSimilarity(OWLNamedIndividual i,
			OWLNamedIndividual j) throws UnknownOWLClassException {
		EWAHCompressedBitmap bmc = ancsBitmapCachedModifiable(i);
		EWAHCompressedBitmap bmd = ancsBitmapCachedModifiable(j);

		return bmc.andCardinality(bmd) / (double) bmc.orCardinality(bmd);
	}

	@Override
	public double getAsymmerticAttributeJaccardSimilarity(OWLClass c, OWLClass d) throws UnknownOWLClassException {
		EWAHCompressedBitmap bmc = ancsBitmapCachedModifiable(c);
		EWAHCompressedBitmap bmd = ancsBitmapCachedModifiable(d);

		return bmc.andCardinality(bmd) / (double) bmd.cardinality();
	}

	@Override
	public double getElementGraphInformationContentSimilarity(
			OWLNamedIndividual i, OWLNamedIndividual j) throws UnknownOWLClassException {
		// TODO - optimize
		Set<Node<OWLClass>> ci = getNamedCommonSubsumers(i, j);
		Set<Node<OWLClass>> cu = getInferredAttributes(i);
		cu.addAll(getInferredAttributes(j));
		double sumICboth = 0;
		double sumICunion = 0;
		for (Node<OWLClass> c : ci) {
			sumICboth += getInformationContentForAttribute(c
					.getRepresentativeElement());
		}
		for (Node<OWLClass> c : cu) {
			sumICunion += getInformationContentForAttribute(c
					.getRepresentativeElement());
		}
		return sumICboth / sumICunion;
	}

	@Override
	public ScoreAttributeSetPair getSimilarityMaxIC(OWLNamedIndividual i,
			OWLNamedIndividual j) throws UnknownOWLClassException {
		
		Set<Node<OWLClass>> atts = getNamedCommonSubsumers(i,j);

		ScoreAttributeSetPair best = new ScoreAttributeSetPair(0.0);
		for (Node<OWLClass> n : atts) {
			OWLClass c = n.getRepresentativeElement();
			Double ic = getInformationContentForAttribute(c);
			if (Math.abs(ic - best.score) < 0.001) {
				// tie for best attribute
				best.addAttributeClass(c);
			}
			if (ic > best.score) {
				best = new ScoreAttributeSetPair(ic, c);
			}
		}
		return best;
	}

	@Override
	public ScoreAttributeSetPair getSimilarityBestMatchAverageAsym(
			OWLNamedIndividual i, OWLNamedIndividual j) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ScoreAttributeSetPair getSimilarityBestMatchAverageAsym(
			OWLNamedIndividual i, OWLNamedIndividual j, Metric metric) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ScoreAttributeSetPair getSimilarityBestMatchAverage(
			OWLNamedIndividual i, OWLNamedIndividual j, Metric metric,
			Direction dir) {
		// TODO Auto-generated method stub
		return null;
	}

	private void getSimilarityMatrixIC(
			OWLNamedIndividual i, OWLNamedIndividual j) throws UnknownOWLClassException {
		// TODO Auto-generated method stub
		Set<OWLClass> cs = getAttributesForElement(i);
		Set<OWLClass> ds = getAttributesForElement(j);
		for (OWLClass c : cs) {
			for (OWLClass d : ds) {
				ScoreAttributeSetPair sap = getLowestCommonSubsumerIC(c,d);
			}			
		}
	}
	
	public ScoreAttributeSetPair getLowestCommonSubsumerIC(OWLClass c,
			OWLClass d) {
		//getNamedLowestCommonSubsumer(c, d);
		return null;
	}


	/**
	 * 
	 * @param c
	 * @param ds
	 * @return
	 * @throws UnknownOWLClassException 
	 */
	@Override
	public List<AttributesSimScores> compareAllAttributes(OWLClass c, Set<OWLClass> ds) throws UnknownOWLClassException {
		List<AttributesSimScores> scoresets = new ArrayList<AttributesSimScores>();

		EWAHCompressedBitmap bmc = this.ancsBitmapCachedModifiable(c);
		int cSize = bmc.cardinality();

		Set<AttributesSimScores> best = new HashSet<AttributesSimScores>();
		Double bestScore = null;
		for (OWLClass d : ds) {
			EWAHCompressedBitmap bmd = this.ancsBitmapCachedModifiable(d);
			int dSize = bmd.cardinality();
			int cadSize = bmc.andCardinality(bmd);
			int cudSize = bmc.orCardinality(bmd);

			AttributesSimScores s = new AttributesSimScores(c,d);
			s.simJScore = cadSize / (double)cudSize;
			s.AsymSimJScore = cadSize / (double) dSize;
			//ClassExpressionPair pair = new ClassExpressionPair(c, d);
			//ScoreAttributePair lcs = getLowestCommonSubsumerIC(pair, cad, null);
			//s.lcsScore = lcs;
			scoresets.add(s);

			if (bestScore == null) {
				best.add(s);
				bestScore = s.simJScore;
			}
			else if (bestScore == s.simJScore) {
				best.add(s);
			}
			else if (s.simJScore > bestScore) {
				bestScore = s.simJScore;
				best = new HashSet<AttributesSimScores>(Collections.singleton(s));
			}
		}
		for (AttributesSimScores s : best) {
			s.isBestMatch = true;
		}

		return scoresets;
	}

	// ----
	// UTIL
	// ----

	OWLClass thing = null;
	Node<OWLClass> thingNode = null;
	public OWLClass owlThing() {
		if (thing == null)
			thing = getSourceOntology().getOWLOntologyManager().getOWLDataFactory().getOWLThing();
		return thing;
	}
	public Node<OWLClass> owlThingNode() {
		if (thingNode == null)
			thingNode = getReasoner().getTopClassNode();
		return thingNode;
	}

}
