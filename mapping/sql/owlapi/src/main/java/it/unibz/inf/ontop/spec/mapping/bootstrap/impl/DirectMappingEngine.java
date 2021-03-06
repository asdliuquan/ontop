package it.unibz.inf.ontop.spec.mapping.bootstrap.impl;

/*
 * #%L
 * ontop-obdalib-owlapi
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import it.unibz.inf.ontop.exception.*;
import it.unibz.inf.ontop.injection.*;
import it.unibz.inf.ontop.spec.mapping.*;
import it.unibz.inf.ontop.spec.mapping.pp.SQLPPMapping;
import it.unibz.inf.ontop.spec.mapping.pp.SQLPPTriplesMap;
import it.unibz.inf.ontop.spec.mapping.pp.impl.OntopNativeSQLPPTriplesMap;
import it.unibz.inf.ontop.spec.mapping.impl.SQLMappingFactoryImpl;
import it.unibz.inf.ontop.model.term.ImmutableFunctionalTerm;
import it.unibz.inf.ontop.spec.ontology.*;
import it.unibz.inf.ontop.spec.ontology.MappingVocabularyExtractor;
import it.unibz.inf.ontop.dbschema.RDBMetadata;
import it.unibz.inf.ontop.dbschema.RDBMetadataExtractionTools;
import it.unibz.inf.ontop.dbschema.DatabaseRelationDefinition;
import it.unibz.inf.ontop.spec.mapping.bootstrap.DirectMappingBootstrapper.BootstrappingResults;
import it.unibz.inf.ontop.utils.LocalJDBCConnectionUtils;
import it.unibz.inf.ontop.utils.UriTemplateMatcher;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Stream;

import static it.unibz.inf.ontop.model.OntopModelSingletons.TERM_FACTORY;


/***
 * 
 * A class that provides manipulation for Direct Mapping
 * 
 * @author Victor
 *
 */
public class DirectMappingEngine {

	private final MappingVocabularyExtractor vocabularyExtractor;

	public static class DefaultBootstrappingResults implements BootstrappingResults {
		private final SQLPPMapping ppMapping;
		private final OWLOntology ontology;

		public DefaultBootstrappingResults(SQLPPMapping ppMapping, OWLOntology ontology) {
			this.ppMapping = ppMapping;
			this.ontology = ontology;
		}

		@Override
		public SQLPPMapping getPPMapping() {
			return ppMapping;
		}

		@Override
		public OWLOntology getOntology() {
			return ontology;
		}
	}

	private static final SQLMappingFactory SQL_MAPPING_FACTORY = SQLMappingFactoryImpl.getInstance();
	private final SpecificationFactory specificationFactory;
	private final SQLPPMappingFactory ppMappingFactory;
	private final OntopSQLCredentialSettings settings;

    private String baseIRI;
	private int currentMappingIndex = 1;

	/**
	 * Entry point.
	 *
	 */
	static BootstrappingResults bootstrap(OntopMappingSQLOWLAPIConfiguration configuration, String baseIRI)
			throws MappingException, OWLOntologyCreationException, MappingBootstrappingException {
		DirectMappingEngine engine = configuration.getInjector().getInstance(DirectMappingEngine.class);
		return engine.bootstrapMappingAndOntology(baseIRI, configuration.loadPPMapping(),
				configuration.loadInputOntology());
	}

	@Inject
	private DirectMappingEngine(OntopSQLCredentialSettings settings, MappingVocabularyExtractor vocabularyExtractor,
								SpecificationFactory specificationFactory,
                                SQLPPMappingFactory ppMappingFactory) {
		this.specificationFactory = specificationFactory;
		this.ppMappingFactory = ppMappingFactory;
		this.settings = settings;
		this.vocabularyExtractor = vocabularyExtractor;
	}

	/**
	 * NOT THREAD-SAFE (not reentrant)
	 */
	private BootstrappingResults bootstrapMappingAndOntology(String baseIRI, Optional<SQLPPMapping> inputPPMapping,
															 Optional<OWLOntology> inputOntology)
			throws MappingBootstrappingException {

		setBaseURI(baseIRI);

		try {
			SQLPPMapping newPPMapping = inputPPMapping.isPresent()
					? extractPPMapping(inputPPMapping.get())
					: extractPPMapping();

			ImmutableOntologyVocabulary newVocabulary = vocabularyExtractor.extractVocabulary(
					newPPMapping.getTripleMaps().stream()
							.flatMap(ax -> ax.getTargetAtoms().stream()));

			OWLOntology newOntology = inputOntology.isPresent()
					? updateOntology(inputOntology.get(), newVocabulary)
					: updateOntology(createEmptyOntology(baseIRI), newVocabulary);

			return new DefaultBootstrappingResults(newPPMapping, newOntology);
		} catch (SQLException | MappingException | OWLOntologyStorageException | OWLOntologyCreationException e) {
			throw new MappingBootstrappingException(e);
		}
	}

	private static OWLOntology createEmptyOntology(String baseIRI) throws OWLOntologyCreationException {
		OWLOntologyManager mng = OWLManager.createOWLOntologyManager();
		return mng.createOntology(IRI.create(baseIRI));
	}


	/*
     * set the base URI used in the ontology
     */
    private void setBaseURI(String prefix) {
        if (prefix.endsWith("#")) {
            this.baseIRI = prefix.replace("#", "/");
        } else if (prefix.endsWith("/")) {
            this.baseIRI = prefix;
        } else this.baseIRI = prefix + "/";
    }


    /***
	 * enrich the ontology according to mappings used in the model
	 * 
	 * @return a new ontology storing all classes and properties used in the mappings
	 *
	 */
	private OWLOntology updateOntology(OWLOntology ontology, ImmutableOntologyVocabulary vocabulary)
			throws OWLOntologyCreationException, OWLOntologyStorageException, SQLException {
		OWLOntologyManager manager = ontology.getOWLOntologyManager();

		OWLDataFactory dataFactory = manager.getOWLDataFactory();
		Set<OWLDeclarationAxiom> declarationAxioms = new HashSet<>();

		//Add all the classes
		for (OClass c :  vocabulary.getClasses()) {
			OWLClass owlClass = dataFactory.getOWLClass(IRI.create(c.getName()));
			declarationAxioms.add(dataFactory.getOWLDeclarationAxiom(owlClass));
		}
		
		//Add all the object properties
		for (ObjectPropertyExpression p : vocabulary.getObjectProperties()){
			OWLObjectProperty property = dataFactory.getOWLObjectProperty(IRI.create(p.getName()));
			declarationAxioms.add(dataFactory.getOWLDeclarationAxiom(property));
		}
		
		//Add all the data properties
		for (DataPropertyExpression p : vocabulary.getDataProperties()){
			OWLDataProperty property = dataFactory.getOWLDataProperty(IRI.create(p.getName()));
			declarationAxioms.add(dataFactory.getOWLDeclarationAxiom(property));
		}

		manager.addAxioms(ontology, declarationAxioms);

		return ontology;		
	}


	/***
	 * extract all the mappings from a datasource
	 *
	 * @return a new OBDA Model containing all the extracted mappings
	 */
	private SQLPPMapping extractPPMapping() throws MappingException, SQLException {
		it.unibz.inf.ontop.spec.mapping.PrefixManager prefixManager = specificationFactory.createPrefixManager(ImmutableMap.of());
		MappingMetadata mappingMetadata = specificationFactory.createMetadata(prefixManager, UriTemplateMatcher.create(Stream.empty()));
		SQLPPMapping emptyPPMapping = ppMappingFactory.createSQLPreProcessedMapping(ImmutableList.of(), mappingMetadata);
		return extractPPMapping(emptyPPMapping);
	}

	private SQLPPMapping extractPPMapping(SQLPPMapping ppMapping)
			throws MappingException, SQLException {
		currentMappingIndex = ppMapping.getTripleMaps().size() + 1;
		return bootstrapMappings(ppMapping);
	}


	/***
	 * extract mappings from given datasource, and insert them into the pre-processed mapping
	 *
	 * Duplicate Exception may happen,
	 * since mapping id is generated randomly and same id may occur
	 */
	private SQLPPMapping bootstrapMappings(SQLPPMapping ppMapping)
			throws SQLException, DuplicateMappingException {
		if (ppMapping == null) {
			throw new IllegalArgumentException("Model should not be null");
		}
		try (Connection conn = LocalJDBCConnectionUtils.createConnection(settings)) {
			RDBMetadata metadata = RDBMetadataExtractionTools.createMetadata(conn);
			// this operation is EXPENSIVE
			RDBMetadataExtractionTools.loadMetadata(metadata, conn, null);
			return bootstrapMappings(metadata, ppMapping);
		}
	}


	private SQLPPMapping bootstrapMappings(RDBMetadata metadata, SQLPPMapping ppMapping) throws DuplicateMappingException {
		if (baseIRI == null || baseIRI.isEmpty())
			this.baseIRI = ppMapping.getMetadata().getPrefixManager().getDefaultPrefix();
		Collection<DatabaseRelationDefinition> tables = metadata.getDatabaseRelations();
		List<SQLPPTriplesMap> mappingAxioms = new ArrayList<>();
		for (DatabaseRelationDefinition td : tables) {
			mappingAxioms.addAll(getMapping(td, baseIRI));
		}

		List<SQLPPTriplesMap> mappings = new ArrayList<>();
		mappings.addAll(ppMapping.getTripleMaps());
		mappings.addAll(mappingAxioms);

		return ppMappingFactory.createSQLPreProcessedMapping(ImmutableList.copyOf(mappings), ppMapping.getMetadata());
	}


	/***
	 * generate a mapping axiom from a table of a database
	 * 
	 * @param table : the data definition from which mappings are extraced
	 * @param baseUri : the base uri needed for direct mapping axiom
	 * 
	 *  @return a List of OBDAMappingAxiom-s
	 */
	private List<SQLPPTriplesMap> getMapping(DatabaseRelationDefinition table, String baseUri) {

		DirectMappingAxiomProducer dmap = new DirectMappingAxiomProducer(baseUri, TERM_FACTORY);

		List<SQLPPTriplesMap> axioms = new ArrayList<>();
		axioms.add(new OntopNativeSQLPPTriplesMap("MAPPING-ID"+ currentMappingIndex, SQL_MAPPING_FACTORY.getSQLQuery(dmap.getSQL(table)), dmap.getCQ(table)));
		currentMappingIndex++;
		
		Map<String, ImmutableList<ImmutableFunctionalTerm>> refAxioms = dmap.getRefAxioms(table);
		for (Map.Entry<String, ImmutableList<ImmutableFunctionalTerm>> e : refAxioms.entrySet()) {
            OBDASQLQuery sqlQuery = SQL_MAPPING_FACTORY.getSQLQuery(e.getKey());
			ImmutableList<ImmutableFunctionalTerm> targetQuery = e.getValue();
            axioms.add(new OntopNativeSQLPPTriplesMap("MAPPING-ID"+ currentMappingIndex, sqlQuery, targetQuery));
			currentMappingIndex++;
		}
		
		return axioms;
	}
}
