package it.unibz.inf.ontop.mssql;

/*
 * #%L
 * ontop-test
 * %%
 * Copyright (C) 2009 - 2014 Free University of Bozen-Bolzano
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */


import it.unibz.inf.ontop.AbstractVirtualModeTest;

/***
 * Tests that mssql identifiers for tables and columns are treated
 * correctly. Especially, that the unquoted identifers are treated as lowercase (for columns)
 */
public class MsSQLIdentifierTest extends AbstractVirtualModeTest {

	private static final String owlfile = "src/test/resources/mssql/identifiers.owl";
	private static final String obdafile = "src/test/resources/mssql/identifiers-mssql.obda";
	private static final String propertyfile = "src/test/resources/mssql/identifiers-mssql.properties";

	public MsSQLIdentifierTest() {
		super(owlfile, obdafile, propertyfile);
	}

	/**
	 * Test use of lowercase column identifiers (also in target)
	 * @throws Exception
	 */
	public void testLowercaseUnquoted() throws Exception {
		String query = "PREFIX : <http://www.semanticweb.org/ontologies/2013/7/untitled-ontology-150#> SELECT ?x WHERE {?x a :Country} ORDER BY ?x";
		String val = runQueryAndReturnStringOfIndividualX(query);
		assertEquals("<http://www.semanticweb.org/ontologies/2013/7/untitled-ontology-150#Country-991>", val);
	}

	public void testUppercaseUnquoted() throws Exception {
		String query = "PREFIX : <http://www.semanticweb.org/ontologies/2013/7/untitled-ontology-150#> SELECT ?x WHERE {?x a :Country2} ORDER BY ?x";
		String val = runQueryAndReturnStringOfIndividualX(query);
		assertEquals("<http://www.semanticweb.org/ontologies/2013/7/untitled-ontology-150#Country2-211>", val);	
	}
		
	public void testLowercaseQuoted() throws Exception {
		String query = "PREFIX : <http://www.semanticweb.org/ontologies/2013/7/untitled-ontology-150#> SELECT ?x WHERE {?x a :Country3} ORDER BY ?x";
		String val = runQueryAndReturnStringOfIndividualX(query);
		assertEquals("<http://www.semanticweb.org/ontologies/2013/7/untitled-ontology-150#Country3-112>", val);
	}
	
	public void testAliasQuoted() throws Exception {
		String query = "PREFIX : <http://www.semanticweb.org/ontologies/2013/7/untitled-ontology-150#> SELECT ?x WHERE {?x a :Country4} ORDER BY ?x";
		String val = runQueryAndReturnStringOfIndividualX(query);
		assertEquals("<http://www.semanticweb.org/ontologies/2013/7/untitled-ontology-150#Country4-111>", val);
	}
}