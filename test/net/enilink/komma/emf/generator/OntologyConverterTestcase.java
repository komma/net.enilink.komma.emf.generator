/*******************************************************************************
 * Copyright (c) 2009, 2010 Fraunhofer IWU and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Fraunhofer IWU - initial API and implementation
 *******************************************************************************/
package net.enilink.komma.emf.generator;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import junit.framework.Assert;

import org.junit.Test;
import org.openrdf.model.ValueFactory;
import org.openrdf.query.Binding;
import org.openrdf.query.BindingSet;
import org.openrdf.query.QueryLanguage;
import org.openrdf.query.TupleQuery;
import org.openrdf.query.TupleQueryResult;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.rio.RDFFormat;
import org.openrdf.sail.NotifyingSail;
import org.openrdf.sail.inferencer.fc.ForwardChainingRDFSInferencer;
import org.openrdf.sail.memory.MemoryStore;

import net.enilink.komma.emf.generator.OntologyConverter;

public class OntologyConverterTestcase {

	private RepositoryConnection connection = null;

	private void loadOwl(File file, String baseURI) throws Exception {

		NotifyingSail sail = new MemoryStore();
		ForwardChainingRDFSInferencer chainingRDFSInferencer = new ForwardChainingRDFSInferencer(
				sail);

		Repository myRepository = new SailRepository(chainingRDFSInferencer);
		myRepository.initialize();
		ValueFactory f = myRepository.getValueFactory();

		connection = myRepository.getConnection();
		connection.add(file, baseURI, RDFFormat.RDFXML);
		connection
				.add(
						new File(
								"../plugins/net.enilink.komma.concepts/META-INF/ontologies/komma.owl"),
						"http://enilink.net/vocab/komma",
						RDFFormat.RDFXML);

	}

	private void closeOwl() {

		try {
			connection.close();
		} catch (RepositoryException e) {
		}
	}

	private List<String> simpleSelect(String sparql, String bindingVarName) {

		ArrayList<String> list = new ArrayList<String>();
		TupleQueryResult result = null;
		try {

			TupleQuery tupleQuery = connection.prepareTupleQuery(
					QueryLanguage.SPARQL, sparql);
			result = tupleQuery.evaluate();

			while (result.hasNext()) {
				BindingSet set = result.next();
				Binding x = set.getBinding(bindingVarName);
				list.add(x.getValue().stringValue());
			}
		} catch (Exception e) {

		} finally {
			try {
				result.close();
			} catch (Exception e) {
			}
		}

		return list;
	}

	@Test
	public void printHelp() throws Exception {
		String args[] = new String[1];
		args[0] = "-help";
		OntologyConverter.main(args);
	}

	@Test
	public void ecore2owl() throws Exception {

		
		  String args[] = new String[5]; args[0] = "-b"; args[1] =
		  "de.test.owl=http://www.eclipse.org/gmf/runtime/1.0.1/notation";
		  args[2] = "-r"; args[3] = "./out/notation.owl"; File f = new File(
		  "../net.enilink.komma.gmf.notation/META-INF/ontologies/notation.ecore"
		  ); args[4] = f.toURI().toString();
		  
		  OntologyConverter.main(args);
		 
		loadOwl(new File("./out/notation.owl"),
				"http://www.eclipse.org/gmf/runtime/1.0.1/notation");
		

		List<String> liste = null;
		
		String queryString1 = "PREFIX abc:<http://enilink.net/vocab/komma#> "
			+ "PREFIX xsd:<http://www.w3.org/2000/01/rdf-schema#> "
			+ "SELECT ?x where {?x xsd:subClassOf abc:KeyValueMap}";

		liste = simpleSelect(queryString1, "x");
		Assert.assertEquals(true, liste.contains("http://www.eclipse.org/gmf/runtime/1.0.1/notation#SortKeyMap"));
		
		queryString1 = "PREFIX abc:<http://enilink.net/vocab/komma#> "
			+ "PREFIX xsd:<http://www.w3.org/2000/01/rdf-schema#> "
			+ "SELECT ?x where {?x xsd:subClassOf abc:LiteralKeyMap}";

		liste = simpleSelect(queryString1, "x");
		Assert.assertEquals(true, liste.contains("http://www.eclipse.org/gmf/runtime/1.0.1/notation#PropertiesSetStylePropertiesMap"));
		
		queryString1 = "PREFIX abc:<http://enilink.net/vocab/komma#> "
			+ "PREFIX xsd:<http://www.w3.org/2000/01/rdf-schema#> "
			+ "SELECT ?x where {?x xsd:subClassOf abc:LiteralValueMap}";

		liste = simpleSelect(queryString1, "x");
		Assert.assertEquals(true, liste.contains("http://www.eclipse.org/gmf/runtime/1.0.1/notation#GuideNodeMap"));
		
		
		closeOwl();

	}

}
