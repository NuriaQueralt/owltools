package owltools.solrj;


import static junit.framework.Assert.*;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import owltools.graph.shunt.OWLShuntGraph;

public class PANTHERTreeTest {

	@Test
	public void testTreeloading() throws IOException{

		// Get the files we need.
		File pFile = getResource("PTHR10000.orig.tree");
//		System.err.println("Processing PANTHER tree: " + pFile.getAbsolutePath());
		PANTHERTree ptree = new PANTHERTree(pFile);
		
//		System.err.println(ptree.getNHXString());
//		System.err.println(ptree.getTreeName());

		// Trivial
		OWLShuntGraph g = ptree.getOWLShuntGraph();
		assertNotNull(g);

//		System.err.println(g.toJSON());
//			assertEquals("At least a string",
//					g.toJSON().getClass().equals(String.class));
			
		Set<String> aSet = ptree.associatedIdentifierSet();
			
		assertTrue("Contains A", aSet.contains("TAIR:locus:2043535"));
		assertTrue("Contains B", aSet.contains("ENTREZ:3405244"));
		assertTrue("Contains C", aSet.contains("UniProtKB:Q4Q8D0"));
		assertTrue("Contains D", aSet.contains("NCBI:XP_650942"));
		assertTrue("Contains E", aSet.contains("ZFIN:ZDB-GENE-050809-127"));
		assertTrue("Contains F", aSet.contains("ENSEMBL:ENSCINP00000026490"));
		assertFalse("Does not contain A", aSet.contains("TAIR:locus:2033535"));
		assertFalse("Does not contain B", aSet.contains("TAIR=locus=2043535"));
		assertFalse("Does not contain C", aSet.contains("ZFIN=ZDB-GENE-050809-127"));
		assertFalse("Does not contain D", aSet.contains(""));
		assertFalse("Does not contain E", aSet.contains("AN7"));
		assertFalse("Does not contain F", aSet.contains(":"));
		assertFalse("Does not contain G", aSet.contains("GO:0022008"));
	}
	
	@Test
	public void testTreeClosures() throws IOException{

		// Get the files we need.
		File pFile = getResource("PTHR31869.orig.tree");
		PANTHERTree ptree = new PANTHERTree(pFile);
			
		// Descendnet closures at leaf.
		Set<String> desc_an3 = ptree.getDescendants("PTHR31869:AN3");
		assertEquals("Just self in descendents (size)", 1, desc_an3.size());
		assertTrue("Just self in descendents", desc_an3.contains("PTHR31869:AN3"));

		// Ancestor closures leaf.
		Set<String> anc_an3 = ptree.getAncestors("PTHR31869:AN3");
		assertTrue("A bunch of ancestors list", anc_an3.contains("PTHR31869:AN3"));
		assertEquals("A bunch of ancestors size", 3, anc_an3.size());
		
		// TODO: More...but global cross-platform/cross-run stable IDs would be helpful.
	}
	
	@Test
	public void testAnnotationClosures() throws IOException{

		// Get the files we need.
		File pFile = getResource("PTHR31869.orig.tree");
		PANTHERTree ptree = new PANTHERTree(pFile);

		Set<String> aa = ptree.getAncestorAnnotations("UniProtKB:Q86KM0");
		assertTrue("ancestor annotations contain self", aa.contains("UniProtKB:Q86KM0"));
		
		Set<String> ad = ptree.getAncestorAnnotations("UniProtKB:Q86KM0");
		assertTrue("descendant annotations contain self", ad.contains("UniProtKB:Q86KM0"));
		
		// TODO: More...but global cross-platform/cross-run stable IDs would be helpful.
		// Or actual manufactured examples for that matter.
	}
	
	// A little helper from Chris stolen from somewhere else...
	protected static File getResource(String name) {
		assertNotNull(name);
		assertFalse(name.length() == 0);
		// TODO: Replace this with a mechanism not relying on the relative path.
		File file = new File("src/test/resources/" + name);
		assertTrue("Requested resource does not exists: "+file, file.exists());
		return file;
	}
}
