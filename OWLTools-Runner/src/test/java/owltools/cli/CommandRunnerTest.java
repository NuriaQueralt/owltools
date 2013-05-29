package owltools.cli;

import org.junit.Test;

/**
 * Tests for {@link CommandRunner}.
 * 
 * these are somewhat ad-hoc at the moment - output is written to stdout;
 * no attempt made to check results
 * 
 */
public class CommandRunnerTest extends AbstractCommandRunnerTest {
	

	@Test
	public void testTableRenderer() throws Exception {
		init();
		load("ceph.obo");
		run("--export-table /tmp/z");
				
	}
	
	
}
