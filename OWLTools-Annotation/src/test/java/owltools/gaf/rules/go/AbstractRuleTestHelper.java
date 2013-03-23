package owltools.gaf.rules.go;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import owltools.OWLToolsTestBasics;
import owltools.gaf.eco.EcoMapperFactory;
import owltools.gaf.eco.TraversingEcoMapper;

public abstract class AbstractRuleTestHelper extends OWLToolsTestBasics {

	protected static TraversingEcoMapper eco = null;
	private static Level elkLogLevel = null;
	private static Logger elkLogger = null;
	
	@BeforeClass
	public static void beforeClass() throws Exception {
		elkLogger = Logger.getLogger("org.semanticweb.elk");
		elkLogLevel = elkLogger.getLevel();
		elkLogger.setLevel(Level.ERROR);
		eco = EcoMapperFactory.createTraversingEcoMapper();
		
	}
	
	@AfterClass
	public static void afterClass() throws Exception {
		if (eco != null) {
			eco.dispose();
			eco = null;
		}
		if (elkLogLevel != null && elkLogger != null) {
			elkLogger.setLevel(elkLogLevel);
			elkLogger = null;
			elkLogLevel = null;
		}
	}
}
