package it.unibz.inf.ontop.answering.reformulation.generation.impl;

/*
 * #%L
 * ontop-reformulation-core
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


import com.google.common.base.Joiner;
import com.google.common.collect.*;
import it.unibz.inf.ontop.answering.reformulation.IRIDictionary;
import it.unibz.inf.ontop.answering.reformulation.generation.dialect.SQLAdapterFactory;
import it.unibz.inf.ontop.answering.reformulation.generation.dialect.SQLDialectAdapter;
import it.unibz.inf.ontop.answering.reformulation.generation.dialect.impl.DB2SQLDialectAdapter;
import it.unibz.inf.ontop.answering.reformulation.generation.utils.XsdDatatypeConverter;
import it.unibz.inf.ontop.answering.reformulation.impl.SQLExecutableQuery;
import it.unibz.inf.ontop.datalog.*;
import it.unibz.inf.ontop.datalog.impl.DatalogAlgebraOperatorPredicates;
import it.unibz.inf.ontop.dbschema.*;
import it.unibz.inf.ontop.exception.IncompatibleTermException;
import it.unibz.inf.ontop.exception.OntopReformulationException;
import it.unibz.inf.ontop.exception.OntopTypingException;
import it.unibz.inf.ontop.injection.OntopReformulationSQLSettings;
import it.unibz.inf.ontop.iq.IntermediateQuery;
import it.unibz.inf.ontop.iq.node.OrderCondition;
import it.unibz.inf.ontop.iq.optimizer.GroundTermRemovalFromDataNodeReshaper;
import it.unibz.inf.ontop.iq.optimizer.PullOutVariableOptimizer;
import it.unibz.inf.ontop.model.term.*;
import it.unibz.inf.ontop.model.term.functionsymbol.BNodePredicate;
import it.unibz.inf.ontop.model.term.functionsymbol.ExpressionOperation;
import it.unibz.inf.ontop.model.term.functionsymbol.Predicate;
import it.unibz.inf.ontop.model.term.functionsymbol.Predicate.COL_TYPE;
import it.unibz.inf.ontop.model.term.functionsymbol.URITemplatePredicate;
import it.unibz.inf.ontop.model.term.impl.TermUtils;
import it.unibz.inf.ontop.model.type.TermType;
import it.unibz.inf.ontop.utils.EncodeForURI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.vocabulary.XMLSchema;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static it.unibz.inf.ontop.datalog.impl.DatalogAlgebraOperatorPredicates.SPARQL_GROUP;
import static it.unibz.inf.ontop.model.OntopModelSingletons.TYPE_FACTORY;
import static it.unibz.inf.ontop.model.term.functionsymbol.Predicate.COL_TYPE.*;

/**
 * This class generates an SQLExecutableQuery from the datalog program coming from the
 * unfolder.
 *
 * This class is NOT thread-safe (attributes values are query-dependent).
 * Thus, an instance of this class should NOT BE SHARED between QuestStatements but be DUPLICATED.
 *
 *
 * @author mrezk, mariano, guohui
 *
 */
public class OneShotSQLGeneratorEngine {

	private static final long serialVersionUID = 7477161929752147045L;

	/**
	 * Formatting template
	 */
    //private static final String VIEW_NAME = "Q%sVIEW%s";
    //private static final String VIEW_ANS_NAME = "Q%sView";
    private static final String VIEW_PREFIX = "Q";
    private static final String VIEW_SUFFIX = "VIEW";
    private static final String VIEW_ANS_SUFFIX = "View";

    private static final String TYPE_STR = "%s AS %s" ;
    private static final String TYPE_SUFFIX = "QuestType";
    private static final String LANG_STR = "%s AS %s";
    private static final String LANG_SUFFIX = "Lang";
    private static final String MAIN_COLUMN_SUFFIX = "";


	private static final String INDENT = "    ";

	private final RDBMetadata metadata;
	private final SQLDialectAdapter sqladapter;
	private final IntermediateQuery2DatalogTranslator iq2DatalogTranslator;


	private boolean generatingREPLACE = true;
	private final boolean distinctResultSet;
	private final String replace1, replace2;

	/**
	 * Mutable (query-dependent)
	 */
	private boolean isDistinct = false;
	private boolean isOrderBy = false;

	@Nullable
	private IRIDictionary uriRefIds;

	private Multimap<Predicate, CQIE> ruleIndex;

	private Map<Predicate, String> sqlAnsViewMap;

	private final ImmutableMap<ExpressionOperation, String> operations;

	private static final org.slf4j.Logger log = LoggerFactory
			.getLogger(OneShotSQLGeneratorEngine.class);
	private final JdbcTypeMapper jdbcTypeMapper;

	OneShotSQLGeneratorEngine(DBMetadata metadata,
							  IRIDictionary iriDictionary,
							  OntopReformulationSQLSettings settings,
							  JdbcTypeMapper jdbcTypeMapper,
							  IntermediateQuery2DatalogTranslator iq2DatalogTranslator) {

		String driverURI = settings.getJdbcDriver()
				.orElseGet(() -> {
					try {
						return DriverManager.getDriver(settings.getJdbcUrl()).getClass().getCanonicalName();
					} catch (SQLException e) {
						// TODO: find a better exception
						throw new RuntimeException("Impossible to get the JDBC driver. Reason: " + e.getMessage());
					}
				});

		if (!(metadata instanceof RDBMetadata)) {
			throw new IllegalArgumentException("Not a DBMetadata!");
		}

		this.metadata = (RDBMetadata)metadata;
		this.sqladapter = SQLAdapterFactory.getSQLDialectAdapter(driverURI,this.metadata.getDbmsVersion(), settings);
		this.operations = buildOperations(sqladapter);
		this.distinctResultSet = settings.isDistinctPostProcessingEnabled();
		this.iq2DatalogTranslator = iq2DatalogTranslator;


		this.generatingREPLACE = settings.isIRISafeEncodingEnabled();

		if (generatingREPLACE) {
			StringBuilder sb1 = new StringBuilder();
			StringBuilder sb2 = new StringBuilder();
			for (Entry<String, String> e : EncodeForURI.TABLE.entrySet()) {
				sb1.append("REPLACE(");
				sb2.append(", '").append(e.getValue()).append("', '").append(e.getKey()).append("')");
			}
			replace1 = sb1.toString();
			replace2 = sb2.toString();
		}
		else {
			replace1 = replace2 = "";
		}

		this.uriRefIds = iriDictionary;
		this.jdbcTypeMapper = jdbcTypeMapper;
 	}

	/**
	 * For clone purposes only
	 */
	private OneShotSQLGeneratorEngine(RDBMetadata metadata, SQLDialectAdapter sqlAdapter, boolean generatingReplace,
                                      String replace1, String replace2, boolean distinctResultSet,
                                      IRIDictionary uriRefIds, JdbcTypeMapper jdbcTypeMapper,
                                      ImmutableMap<ExpressionOperation, String> operations,
									  IntermediateQuery2DatalogTranslator iq2DatalogTranslator) {
		this.metadata = metadata;
		this.sqladapter = sqlAdapter;
		this.operations = operations;
		this.generatingREPLACE = generatingReplace;
		this.replace1 = replace1;
		this.replace2 = replace2;
		this.distinctResultSet = distinctResultSet;
		this.uriRefIds = uriRefIds;
		this.jdbcTypeMapper = jdbcTypeMapper;
		this.iq2DatalogTranslator = iq2DatalogTranslator;
	}

	private static ImmutableMap<ExpressionOperation, String> buildOperations(SQLDialectAdapter sqladapter) {
		ImmutableMap.Builder<ExpressionOperation, String> builder = new ImmutableMap.Builder<ExpressionOperation, String>()
				.put(ExpressionOperation.ADD, "%s + %s")
				.put(ExpressionOperation.SUBTRACT, "%s - %s")
				.put(ExpressionOperation.MULTIPLY, "%s * %s")
				.put(ExpressionOperation.DIVIDE, "(1.0 * %s) / %s")
				.put(ExpressionOperation.ABS, "ABS(%s)")
				.put(ExpressionOperation.CEIL, sqladapter.ceil())
				.put(ExpressionOperation.FLOOR, "FLOOR(%s)")
				.put(ExpressionOperation.ROUND, sqladapter.round())
				.put(ExpressionOperation.RAND, sqladapter.rand())
				.put(ExpressionOperation.EQ, "%s = %s")
				.put(ExpressionOperation.NEQ, "%s <> %s")
				.put(ExpressionOperation.GT, "%s > %s")
				.put(ExpressionOperation.GTE, "%s >= %s")
				.put(ExpressionOperation.LT, "%s < %s")
				.put(ExpressionOperation.LTE, "%s <= %s")
				.put(ExpressionOperation.AND, "%s AND %s")
				.put(ExpressionOperation.OR, "%s OR %s")
				.put(ExpressionOperation.NOT, "NOT %s")
				.put(ExpressionOperation.IS_NULL, "%s IS NULL")
				.put(ExpressionOperation.IS_NOT_NULL, "%s IS NOT NULL")
				.put(ExpressionOperation.IS_TRUE, "%s IS TRUE")
				.put(ExpressionOperation.SQL_LIKE, "%s LIKE %s")
				.put(ExpressionOperation.STR_STARTS, sqladapter.strStartsOperator())
				.put(ExpressionOperation.STR_ENDS, sqladapter.strEndsOperator())
				.put(ExpressionOperation.CONTAINS, sqladapter.strContainsOperator())

				.put(ExpressionOperation.NOW, sqladapter.dateNow());

		try {
			builder.put(ExpressionOperation.STRUUID, sqladapter.strUuid());
		} catch (UnsupportedOperationException e) {
			// ignore
		}
		try {
			builder.put(ExpressionOperation.UUID, sqladapter.uuid());
		} catch (UnsupportedOperationException e) {
			// ignore
		}
		return builder.build(

		);
	}

	/**
	 * SQLGenerator must not be shared between threads
	 * but CLONED.
	 *
	 * @return A cloned object without any query-dependent value
	 */
	@Override
	public OneShotSQLGeneratorEngine clone() {
		return new OneShotSQLGeneratorEngine(metadata, sqladapter, generatingREPLACE,
				replace1, replace2, distinctResultSet, uriRefIds, jdbcTypeMapper, operations, iq2DatalogTranslator);
	}

	/**
	 * Generates and SQL query ready to be executed by Quest. Each query is a
	 * SELECT FROM WHERE query. To know more about each of these see the inner
	 * method descriptions. Observe that the SQL itself will be done by
	 * {@link #generateQuery}
	 *
	 */
	public SQLExecutableQuery generateSourceQuery(IntermediateQuery intermediateQuery, ImmutableList<String> signature)
			throws OntopReformulationException {

		IntermediateQuery normalizedQuery = normalizeIQ(intermediateQuery);

		DatalogProgram queryProgram = iq2DatalogTranslator.translate(normalizedQuery);

		normalizeProgram(queryProgram);

		DatalogDependencyGraphGenerator depGraph = new DatalogDependencyGraphGenerator(
				queryProgram);

		sqlAnsViewMap = new HashMap<>();

		ruleIndex = depGraph.getRuleIndex();

		List<Predicate> predicatesInBottomUp = depGraph
				.getPredicatesInBottomUp();

		List<Predicate> extensionalPredicates = depGraph
				.getExtensionalPredicates();

		isDistinct = hasSelectDistinctStatement(queryProgram);
		isOrderBy = hasOrderByClause(queryProgram);
		if (queryProgram.getQueryModifiers().hasModifiers()) {
			final String outerViewName = "SUB_QVIEW";
			String subquery = generateQuery(signature, ruleIndex, predicatesInBottomUp, extensionalPredicates);

			String modifier;

			List<OrderCondition> conditions = queryProgram.getQueryModifiers().getSortConditions();

			List<Variable> groupby = queryProgram.getQueryModifiers().getGroupConditions();
			// if (!groupby.isEmpty()) {
			// subquery += "\n" + sqladapter.sqlGroupBy(groupby, "") + " " +
			// havingStr + "\n";
			// }
			// List<OrderCondition> conditions =
			// query.getQueryModifiers().getSortConditions();

			long limit = queryProgram.getQueryModifiers().getLimit();
			long offset = queryProgram.getQueryModifiers().getOffset();

			if (!conditions.isEmpty()) {
				modifier = sqladapter.sqlOrderByAndSlice(conditions, outerViewName, limit, offset)
						+ "\n";
			}
			else if (limit != -1 || offset != -1) {
				modifier = sqladapter.sqlSlice(limit, offset) + "\n";
			}
			else {
				modifier = "";
			}

			String sql = "SELECT *\n";
			sql += "FROM (\n";
			sql += subquery + "\n";
			sql += ") " + outerViewName + "\n";
			sql += modifier;
			return new SQLExecutableQuery(sql, signature);
		} else {
			String sqlQuery = generateQuery(signature, ruleIndex, predicatesInBottomUp, extensionalPredicates);
			return new SQLExecutableQuery(sqlQuery, signature);
		}
	}

	private IntermediateQuery normalizeIQ(IntermediateQuery intermediateQuery) {

		IntermediateQuery groundTermFreeQuery = new GroundTermRemovalFromDataNodeReshaper()
				.optimize(intermediateQuery);
		log.debug("New query after removing ground terms: \n" + groundTermFreeQuery);

		IntermediateQuery queryAfterPullOut = new PullOutVariableOptimizer().optimize(groundTermFreeQuery);
		log.debug("New query after pulling out equalities: \n" + queryAfterPullOut);

		return queryAfterPullOut;
	}

    private boolean hasSelectDistinctStatement(DatalogProgram query) {
		boolean toReturn = false;
		if (query.getQueryModifiers().hasModifiers()) {
			toReturn = query.getQueryModifiers().isDistinct();
		}
		return toReturn;
	}

	private boolean hasOrderByClause(DatalogProgram query) {
		boolean toReturn = false;
		if (query.getQueryModifiers().hasModifiers()) {
			final List<OrderCondition> conditions = query.getQueryModifiers()
					.getSortConditions();
			toReturn = (!conditions.isEmpty());
		}
		return toReturn;
	}

	/**
	 * Main method. Generates the full SQL query, taking into account
	 * limit/offset/order by. An important part of this program is
	 * {@link #createViewFrom}
	 * that will create a view for every ans predicate in the Datalog input
	 * program.
	 *
	 * @param signature
	 *            The Select variables in the SPARQL query
	 * @param ruleIndex
	 *            The index that maps intentional predicates to its rules
	 * @param predicatesInBottomUp
	 *            The topologically ordered predicates in
	 *            <code> query </code>.
	 * @param extensionalPredicates
	 *            The predicates that are not defined by any rule in <code>
	 *            query </code>
	 * @return
	 */
	private String generateQuery(List<String> signature,
								 Multimap<Predicate, CQIE> ruleIndex,
								 List<Predicate> predicatesInBottomUp,
								 List<Predicate> extensionalPredicates) throws OntopReformulationException {

		int numPreds = predicatesInBottomUp.size();
		int i = 0;

		 Map<Predicate, ParserViewDefinition> subQueryDefinitions = new HashMap<>();

		TypeExtractor.TypeResults typeResults;
		try {
			typeResults = TypeExtractor.extractTypes(ruleIndex, predicatesInBottomUp, metadata);
			/*
			 * Currently, incompatible terms are treated as a reformulation error
			 */
		} catch (IncompatibleTermException e) {
			throw new OntopTypingException(e.getMessage());
		}

		ImmutableMap<CQIE, ImmutableList<Optional<TermType>>> termTypeMap = typeResults.getTermTypeMap();
		ImmutableMap<Predicate, ImmutableList<COL_TYPE>> castTypeMap = typeResults.getCastTypeMap();

		/**
		 * ANS i > 1
		 */

		// create a view for every ans predicate in the Datalog input program.
		while (i < numPreds - 1) {
			Predicate pred = predicatesInBottomUp.get(i);
			if (extensionalPredicates.contains(pred)) {
				/*
				 * extensional predicates are defined by DBs
				 */
			} else {
				ParserViewDefinition view = createViewFrom(pred, metadata, ruleIndex, subQueryDefinitions,
						termTypeMap, castTypeMap.get(pred));

				subQueryDefinitions.put(pred, view);
			}
			i++;
		}

		/**
		 * ANS 1
		 */

		// This should be ans1, and the rules defining it.
		Predicate predAns1 = predicatesInBottomUp.get(i);
		Collection<CQIE> ansrules = ruleIndex.get(predAns1);

		List<String> queryStrings = Lists.newArrayListWithCapacity(ansrules
				.size());

		
		/* Main loop, constructing the SPJ query for each CQ */

		for (CQIE cq : ansrules) {

			/*
			 * Here we normalize so that the form of the CQ is as close to the
			 * form of a normal SQL algebra as possible,
			 */
			boolean isAns1 = true;
			String querystr = generateQueryFromSingleRule(cq, signature, isAns1, castTypeMap.get(predAns1),
					subQueryDefinitions, termTypeMap.get(cq));

			queryStrings.add(querystr);
		}

		StringBuilder result = createUnionFromSQLList(queryStrings);

		return result.toString();
	}



	/**
	 * Takes a list of SQL strings, and returns SQL1 UNION SQL 2 UNION.... This
	 * method complements {@link #generateQueryFromSingleRule}
	 *
	 * @param queriesStrings list
	 *                       of SQL strings
	 * @return Union of sql queries
	 */
	private StringBuilder createUnionFromSQLList(List<String> queriesStrings) {
		Iterator<String> queryStringIterator = queriesStrings.iterator();
		StringBuilder result = new StringBuilder();
		if (queryStringIterator.hasNext()) {
			result.append(queryStringIterator.next());
		}

		String UNION;
		if (isDistinct && !distinctResultSet) {
			UNION = "UNION";
		} else {
			UNION = "UNION ALL";
		}
		while (queryStringIterator.hasNext()) {
			result.append("\n");
			result.append(UNION);
			result.append("\n");
			result.append(queryStringIterator.next());
		}
		return result;
	}

	/**
	 * Takes 1 single Datalog rule <code> cq </code> and return the SQL
	 * translation of that rule. It is a helper method for
	 * {@link #generateQuery}
	 *
	 * @param cq
	 * @param signature
	 * @param castDatatypes
	 * @param subQueryDefinitions
	 * @param termTypes
	 */
	public String generateQueryFromSingleRule(CQIE cq, List<String> signature,
											  boolean isAns1, List<COL_TYPE> castDatatypes,
											  Map<Predicate, ParserViewDefinition> subQueryDefinitions,
											  ImmutableList<Optional<TermType>> termTypes) {
		QueryAliasIndex index = new QueryAliasIndex(cq, subQueryDefinitions);

		boolean innerdistincts = false;

		// && numberOfQueries == 1
		if (isDistinct && !distinctResultSet) {
			innerdistincts = true;
		}

		String FROM = getFROM(cq.getBody(), index);
		String WHERE = getWHERE(cq.getBody(), index);

		String SELECT = getSelectClause(signature, cq, index, innerdistincts, isAns1, castDatatypes, termTypes);
		String GROUP = getGroupBy(cq.getBody(), index);
		String HAVING = getHaving(cq.getBody(), index);

		String querystr = SELECT + FROM + WHERE + GROUP + HAVING;
		return querystr;
	}

	private String getHaving(List<Function> body, QueryAliasIndex index) {
		StringBuilder result = new StringBuilder();
		List <Term> conditions = new LinkedList<Term> ();
		List <Function> condFunctions = new LinkedList<Function> ();
		//List<Variable> varsInHaving = Lists.newArrayList();
		for (Function atom : body) {
			if (atom.getFunctionSymbol().equals(DatalogAlgebraOperatorPredicates.SPARQL_HAVING)) {
				conditions = atom.getTerms();
				break;
			}
		}
		if (conditions.isEmpty()) {
			return "";
		}

		for(Term cond : conditions){
			condFunctions.add((Function) cond);
		}

		Set<String> condSet = getBooleanConditionsString(condFunctions, index);

//		List<String> groupReferences = Lists.newArrayList();

//		for(Variable var : varsInGroupBy) {
//			Collection<String> references = index.columnReferences.get(var);
//			groupReferences.addAll(references);
//		}
//		
//		if(!groupReferences.isEmpty()) {
//			result.append(" GROUP BY " );
//			Joiner.on(" , ").appendTo(result, groupReferences);
//		}

		result.append(" HAVING ( ");
		for (String c: condSet) {
			result.append(c);
		}
		result.append(" ) ");
		return result.toString();
	}

	private String getGroupBy(List<Function> body, QueryAliasIndex index) {
		StringBuilder result = new StringBuilder();

		List<Variable> varsInGroupBy = Lists.newArrayList();
		for (Function atom : body) {
			if (atom.getFunctionSymbol().equals(SPARQL_GROUP)) {
				varsInGroupBy.addAll(atom.getVariables());
			}
		}

		List<String> groupReferences = Lists.newArrayList();

		for(Variable var : varsInGroupBy) {
			index.columnReferences.get(var).stream()
					.map(QualifiedAttributeID::getSQLRendering)
					.forEach(groupReferences::add);
		}

		if(!groupReferences.isEmpty()) {
			result.append(" GROUP BY " );
			Joiner.on(" , ").appendTo(result, groupReferences);
		}

		return result.toString();
	}

	/**
	 * Normalizations of the Datalog program requirend by the Datalog to SQL translator
	 */
	private void normalizeRule(CQIE cq) {

		DatalogNormalizer.foldJoinTrees(cq);

		DatalogNormalizer.addMinimalEqualityToLeftJoin(cq);
	}

	/**
	 * @param program
	 */
	private void normalizeProgram(DatalogProgram program) {
		for (CQIE rule : program.getRules()) {
			normalizeRule(rule);
		}
		log.debug("Program normalized for SQL translation: \n"+program);
	}

	/**
	 * This Method was created to handle the semantics of OPTIONAL when there
	 * are multiple mappings or Unions. It will take mappings of the form
	 * <ul>
	 * <li>Concept <- definition1</li>
	 * <li>Concept <- definition2</li>
	 * </ul>
	 * And will generate a view of the form
	 * <ul>
	 * <li>QConceptView = definition1 UNION definition2
	 * </ul>
	 * This view is stored in the <code>metadata </code>. See DBMetadata
	 *
	 * The idea is to use the view definition in the case of Union in the
	 * Optionals/LeftJoins
	 *
	 * @param ruleIndex
	 * @param subQueryDefinitions
	 * @param termTypeMap
	 *@param castTypes @throws OBDAException
	 *
	 * @throws Exception
	 */

	private ParserViewDefinition createViewFrom(Predicate pred, RDBMetadata metadata,
												Multimap<Predicate, CQIE> ruleIndex,
												Map<Predicate, ParserViewDefinition> subQueryDefinitions,
												ImmutableMap<CQIE, ImmutableList<Optional<TermType>>> termTypeMap,
												ImmutableList<COL_TYPE> castTypes) {

		/* Creates BODY of the view query */

		Collection<CQIE> ruleList = ruleIndex.get(pred);

		String unionView;

		List<String> sqls = Lists.newArrayListWithExpectedSize(ruleList.size());

		int headArity = 0;

		for (CQIE rule : ruleList) {
			Function cqHead = rule.getHead();

			headArity = cqHead.getTerms().size();

			List<String> varContainer = cqHead.getVariables().stream()
					.map(Variable::getName)
					.collect(Collectors.toList());

			/* Creates the SQL for the View */
			String sqlQuery = generateQueryFromSingleRule(rule, varContainer,
					false, castTypes, subQueryDefinitions, termTypeMap.get(rule));

			sqls.add(sqlQuery);
		}

		if (sqls.size() == 1) {
			unionView = sqls.iterator().next();
		} else {
			unionView = "(" + Joiner.on(")\n UNION ALL \n (").join(sqls) + ")";
		}

		QuotedIDFactory idFactory = metadata.getQuotedIDFactory();

		Set<RelationID> alreadyAllocatedViewNames = subQueryDefinitions.values().stream()
				.map(ParserViewDefinition::getID)
				.collect(Collectors.toSet());

		String safePredicateName = escapeName(pred.getName());
		String viewname = sqladapter.nameView(VIEW_PREFIX, safePredicateName, VIEW_ANS_SUFFIX,
				alreadyAllocatedViewNames);
		RelationID viewId = idFactory.createRelationID(null, viewname);

		List<QualifiedAttributeID> columnIds = Lists.newArrayListWithExpectedSize(3 * headArity);

		// Hard coded variable names
		for (int i = 0; i < headArity; i++) {
			columnIds.add(new QualifiedAttributeID(viewId,
					idFactory.createAttributeID(sqladapter.sqlQuote("v" + i + TYPE_SUFFIX))));
			columnIds.add(new QualifiedAttributeID(viewId,
					idFactory.createAttributeID(sqladapter.sqlQuote("v" + i + LANG_SUFFIX))));
			columnIds.add(new QualifiedAttributeID(viewId,
					idFactory.createAttributeID(sqladapter.sqlQuote("v" + i))));
		}

		// Creates a view outside the DBMetadata (specific to this sub-query)
		ParserViewDefinition view = new ParserViewDefinition(viewId, unionView);
		columnIds.stream().forEach(view::addAttribute);

		sqlAnsViewMap.put(pred, unionView);

		return view;
	}

	/**
	 * Escapes view names.
	 */
	private static String escapeName(String name) {
		return name.replace('.', '_').replace(':', '_').replace('/', '_').replace(' ', '_');
	}

	/***
	 * Returns a string with boolean conditions formed with the boolean atoms
	 * found in the atoms list.
	 */
	private Set<String> getBooleanConditionsString(
			List<Function> atoms, QueryAliasIndex index) {
		Set<String> conditions = new LinkedHashSet<String>();
		for (int atomidx = 0; atomidx < atoms.size(); atomidx++) {
			Term innerAtom = atoms.get(atomidx);
			Function innerAtomAsFunction = (Function) innerAtom;
			// Boolean expression
			if (innerAtomAsFunction.isOperation()) {
				String condition = getSQLCondition(innerAtomAsFunction, index);

				conditions.add(condition);
			} else if (innerAtomAsFunction.isDataTypeFunction()) {

				String condition = getSQLString(innerAtom, index, false);
				conditions.add(condition);
			}
		}
		return conditions;
	}

	/***
	 * Returns the SQL for an atom representing an SQL condition (booleans).
	 */
	private String getSQLCondition(Function atom, QueryAliasIndex index) {
		Predicate functionSymbol = atom.getFunctionSymbol();
		if (isUnary(atom)) {
			// For unary boolean operators, e.g., NOT, IS NULL, IS NOT NULL.
			// added also for IS TRUE
			String expressionFormat = operations.get(functionSymbol);
			Term term = atom.getTerm(0);
			String column = getSQLString(term, index, false);
			if (expressionFormat.contains("NOT %s")) {
				// find data type of term and evaluate accordingly
				// int type = 8;
				if (term instanceof Function) {
					Function f = (Function) term;
					if (!f.isDataTypeFunction())
						return String.format(expressionFormat, column);
				}
				int type = getVariableDataType(term, index);
				switch (type) {
					case Types.INTEGER:
					case Types.BIGINT:
					case Types.DOUBLE:
					case Types.FLOAT:
						return String.format("%s = 0", column);
					case Types.BOOLEAN:
						return String.format("NOT %s", column);
					case Types.VARCHAR:
						return String.format("NOT LENGTH(%s) > 0", column);
					default:
						throw new UnsupportedOperationException("Unsupported type: " + type);
				}
			}
			if (expressionFormat.contains("IS TRUE")) {
				// find data type of term and evaluate accordingly
				int type = getVariableDataType(term, index);
				switch (type) {
					case Types.INTEGER:
					case Types.BIGINT:
					case Types.DOUBLE:
					case Types.FLOAT:
						return String.format("%s != 0", column);
					case Types.BOOLEAN:
						return String.format("%s", column);
					case Types.VARCHAR:
						return String.format("LENGTH(%s) > 0", column);
					default:
						throw new UnsupportedOperationException("Unsupported type: " + type);
				}
			}
			return String.format(expressionFormat, column);
		} else if (isBinary(atom)) {
			// For binary boolean operators, e.g., AND, OR, EQ, GT, LT, etc.
			// _
			String expressionFormat = operations.get(functionSymbol);
			Term left = atom.getTerm(0);
			Term right = atom.getTerm(1);
			String leftOp = getSQLString(left, index, true);
			String rightOp = getSQLString(right, index, true);

			return String.format("(" + expressionFormat + ")", leftOp,
					rightOp);
		} else {
			if (functionSymbol == ExpressionOperation.REGEX) {
				boolean caseinSensitive = false;
				boolean multiLine = false;
				boolean dotAllMode = false;
				if (atom.getArity() == 3) {
					if (atom.getTerm(2).toString().contains("i")) {
						caseinSensitive = true;
					}
					if (atom.getTerm(2).toString().contains("m")) {
						multiLine = true;
					}
					if (atom.getTerm(2).toString().contains("s")) {
						dotAllMode = true;
					}
				}
				Term p1 = atom.getTerm(0);
				Term p2 = atom.getTerm(1);

				String column = getSQLString(p1, index, false);
				String pattern = getSQLString(p2, index, false);
				String sqlRegex = sqladapter.sqlRegex(column, pattern, caseinSensitive,
						multiLine, dotAllMode);
				return sqlRegex;
			} else {
				throw new RuntimeException("The builtin function "
						+ functionSymbol.toString() + " is not supported yet!");
			}
		}
	}

	/**
	 * Returns the table definition for these atoms. By default, a list of atoms
	 * represents JOIN or LEFT JOIN of all the atoms, left to right. All boolean
	 * atoms in the list are considered conditions in the ON clause of the JOIN.
	 *
	 * <p>
	 * If the list is a LeftJoin, then it can only have 2 data atoms, and it HAS
	 * to have 2 data atoms.
	 *
	 * <p>
	 * If process boolean operators is enabled, all boolean conditions will be
	 * added to the ON clause of the first JOIN.
	 *
	 * @param atoms
	 * @param index
	 * @param isTopLevel
	 *            indicates if the list of atoms is actually the main body of
	 *            the conjunctive query. If it is, no JOIN is generated, but a
	 *            cross product with WHERE clause. Moreover, the isLeftJoin
	 *            argument will be ignored.
	 *
	 * @return
	 */
	private String getTableDefinitions(List<Function> atoms,
									   QueryAliasIndex index, boolean isTopLevel, boolean isLeftJoin, boolean parenthesis,
									   String indent) {
		/*
		 * We now collect the view definitions for each data atom each
		 * condition, and each each nested Join/LeftJoin
		 */
		List<String> tableDefinitions = new LinkedList<>();
		for (Function a : atoms) {
			String definition = getTableDefinition(a, index, indent + INDENT);
			if (!definition.isEmpty()) {
				tableDefinitions.add(definition);
			}
		}

		/*
		 * Now we generate the table definition, this will be either a comma
		 * separated list for TOP level (FROM clause) or a Join/LeftJoin
		 * (possibly nested if there are more than 2 table definitions in the
		 * current list) in case this method was called recursively.
		 */
		StringBuilder tableDefinitionsString = new StringBuilder();

		int size = tableDefinitions.size();
		if (isTopLevel) {
			if (size == 0) {
				tableDefinitionsString.append("(" + sqladapter.getDummyTable()
						+ ") tdummy ");

			} else {
				Iterator<String> tableDefinitionsIterator = tableDefinitions
						.iterator();
				tableDefinitionsString.append(indent);
				tableDefinitionsString.append(tableDefinitionsIterator.next());
				while (tableDefinitionsIterator.hasNext()) {
					tableDefinitionsString.append(",\n");
					tableDefinitionsString.append(indent);
					tableDefinitionsString.append(tableDefinitionsIterator
							.next());
				}
			}
		} else {
			/*
			 * This is actually a Join or LeftJoin, so we form the JOINs/LEFT
			 * JOINs and the ON clauses
			 */
			String JOIN_KEYWORD;
			if (isLeftJoin) {
				JOIN_KEYWORD = "LEFT OUTER JOIN";
			} else {
				JOIN_KEYWORD = "JOIN";
			}
			String JOIN;

			//add parenthesis
			String NESTEDJOIN = "" + indent + "" + indent + "%s\n" + indent
					+ JOIN_KEYWORD + "\n" + indent + "(%s)" + indent + "";

			if(parenthesis){
				JOIN = NESTEDJOIN;
			}
			else {
				JOIN = "" + indent + "" + indent + "%s\n" + indent
						+ JOIN_KEYWORD + "\n" + indent + "%s" + indent + "";
			}

			if (size == 0) {
				throw new RuntimeException(
						"Cannot generate definition for empty data");
			}
			if (size == 1) {
				return tableDefinitions.get(0);
			}

			/*
			 * To form the JOIN we will cycle through each data definition,
			 * nesting the JOINs as we go. The conditions in the ON clause will
			 * go on the TOP level only.
			 */
			String currentJoin = String.format(JOIN,
					tableDefinitions.get(size - 2),
					tableDefinitions.get(size - 1));
			tableDefinitions.remove(size - 1);
			tableDefinitions.remove(size - 2);

			int currentSize = tableDefinitions.size();
			while (currentSize > 0) {
				currentJoin = String.format(NESTEDJOIN,
						tableDefinitions.get(currentSize - 1), currentJoin);
				tableDefinitions.remove(currentSize - 1);
				currentSize = tableDefinitions.size();
			}
			tableDefinitions.add(currentJoin);

			tableDefinitionsString.append(currentJoin);


			/*
			 * If there are ON conditions we add them now. We need to remove the
			 * last parenthesis ')' and replace it with ' ON %s)' where %s are
			 * all the conditions
			 */
			String conditions = getConditionsString(atoms, index, true,
					indent);

//			if (conditions.length() > 0
//					&& tableDefinitionsString.lcastIndexOf(")") != -1) {
//				int lastidx = tableDefinitionsString.lastIndexOf(")");
//				tableDefinitionsString.delete(lastidx,
//						tableDefinitionsString.length());
//				String ON_CLAUSE = String.format("ON\n%s\n " + indent + ")",
//						conditions);
//				tableDefinitionsString.append(ON_CLAUSE);
//			}
			String ON_CLAUSE = String.format(" ON\n%s\n " + indent, conditions);
			tableDefinitionsString.append(ON_CLAUSE);


		}
		return  tableDefinitionsString.toString() ;
	}

	/**
	 * Returns the table definition for the given atom. If the atom is a simple
	 * table or view, then it returns the value as defined by the
	 * QueryAliasIndex. If the atom is a Join or Left Join, it will call
	 * getTableDefinitions on the nested term list.
	 */
	private String getTableDefinition(Function atom, QueryAliasIndex index,
									  String indent) {
		Predicate predicate = atom.getFunctionSymbol();

		if (atom.isOperation()
				|| atom.isDataTypeFunction()) {
			// These don't participate in the FROM clause
			return "";
		} else if (atom.isAlgebraFunction()) {

			if (predicate == SPARQL_GROUP) {
				return "";
			}
			List<Function> innerTerms = new ArrayList<>(atom.getTerms().size());

			boolean parenthesis = false;


			if (predicate == DatalogAlgebraOperatorPredicates.SPARQL_JOIN || predicate == DatalogAlgebraOperatorPredicates.SPARQL_LEFTJOIN) {

				boolean isLeftJoin = false;

				if (predicate == DatalogAlgebraOperatorPredicates.SPARQL_LEFTJOIN) {
					isLeftJoin = true;
				}

				int i =0;
				for (Term term : atom.getTerms()){
					Function innerTerm1 = (Function) term;
					if(innerTerm1.isAlgebraFunction()){
						//nested joins we need to add parenthesis later
						parenthesis = true;
					}
					else if(isLeftJoin && i == 1){
						//in case of left join we  want to add the parenthesis
						// only for the right tables
						//we ignore nested joins from the left tables

						parenthesis = false;
					}
					innerTerms.add(innerTerm1);
					i++;
				}
				String tableDefinitions =  getTableDefinitions(innerTerms,
						index, false, isLeftJoin, parenthesis, indent + INDENT );
				return tableDefinitions;
			}

		}


		/*
		 * This is a data atom
		 */
		String def = index.getViewDefinition(atom);
		return def;
	}

	private String getFROM(List<Function> atoms, QueryAliasIndex index) {
		String tableDefinitions = getTableDefinitions(atoms, index, true,
				false, false, "");
		return "\n FROM \n" + tableDefinitions;
	}

	/**
	 * Generates all the conditions on the given atoms, e.g., shared variables
	 * and boolean conditions. This string can then be used to form a WHERE or
	 * an ON clause.
	 *
	 * <p>
	 * The method assumes that no variable in this list (or nested ones) referes
	 * to an upper level one.
	 */
	private String getConditionsString(List<Function> atoms,
									   QueryAliasIndex index, boolean processShared, String indent) {

		Set<String> equalityConditions = new LinkedHashSet<>();

		// if (processShared)

		// guohui: After normalization, do we have shared variables?
		// TODO: should we remove this ??
		Set<String> conditionsSharedVariablesAndConstants = getConditionsSharedVariablesAndConstants(
				atoms, index, processShared);
		equalityConditions.addAll(conditionsSharedVariablesAndConstants);
		Set<String> booleanConditions = getBooleanConditionsString(
				atoms, index);

		Set<String> conditions = new LinkedHashSet<>();
		conditions.addAll(equalityConditions);
		conditions.addAll(booleanConditions);

		/*
		 * Collecting all the conditions in a single string for the ON or WHERE
		 * clause
		 */
		StringBuilder conditionsString = new StringBuilder();
		Iterator<String> conditionsIterator = conditions.iterator();
		if (conditionsIterator.hasNext()) {
			conditionsString.append(indent);
			conditionsString.append(conditionsIterator.next());
		}
		while (conditionsIterator.hasNext()) {
			conditionsString.append(" AND\n");
			conditionsString.append(indent);
			conditionsString.append(conditionsIterator.next());
		}
		return conditionsString.toString();
	}

	/**
	 * Returns the set of variables that participate data atoms (either in this
	 * atom directly or in nested ones). This will recursively collect the
	 * variables references in in this atom, exlcuding those on the right side
	 * of left joins.
	 *
	 * @param atom
	 * @return
	 */
	private Set<Variable> getVariableReferencesWithLeftJoin(Function atom) {
		if (atom.isDataFunction()) {
			Set<Variable> variables = new LinkedHashSet<>();
			TermUtils.addReferencedVariablesTo(variables, atom);
			return variables;
		}
		if (atom.isOperation()) {
			return Collections.emptySet();
		}
		if (atom.isDataTypeFunction()) {
			return Collections.emptySet();
		}
		/*
		 * we have an algebra operator (join or left join) if its a join, we need
		 * to collect all the variables of each nested atom., if its a left
		 * join, only of the first data/algebra atom (the left atom).
		 */
		boolean isLeftJoin = false;
		boolean foundFirstDataAtom = false;

		if (atom.getFunctionSymbol() == DatalogAlgebraOperatorPredicates.SPARQL_LEFTJOIN) {
			isLeftJoin = true;
		}
		Set<Variable> innerVariables = new LinkedHashSet<>();
		for (Term t : atom.getTerms()) {
			if (isLeftJoin && foundFirstDataAtom) {
				break;
			}
			Function asFunction = (Function) t;
			if (asFunction.isOperation()) {
				continue;
			}
			innerVariables
					.addAll(getVariableReferencesWithLeftJoin(asFunction));
			foundFirstDataAtom = true;
		}
		return innerVariables;

	}

	/**
	 * Returns a list of equality conditions that reflect the semantics of the
	 * shared variables in the list of atoms.
	 * <p>
	 * The method assumes that no variables are shared across deeper levels of
	 * nesting (through Join or LeftJoin atoms), it will not call itself
	 * recursively. Nor across upper levels.
	 *
	 * <p>
	 * When generating equalities recursively, we will also generate a minimal
	 * number of equalities. E.g., if we have A(x), Join(R(x,y), Join(R(y,
	 * x),B(x))
	 *
	 */
	private Set<String> getConditionsSharedVariablesAndConstants(
			List<Function> atoms, QueryAliasIndex index, boolean processShared) {
		Set<String> equalities = new LinkedHashSet<>();

		Set<Variable> currentLevelVariables = new LinkedHashSet<>();
		if (processShared) {
			for (Function atom : atoms) {
				currentLevelVariables
						.addAll(getVariableReferencesWithLeftJoin(atom));
			}
		}

		/*
		 * For each variable we collect all the columns that shold be equated
		 * (due to repeated positions of the variable). then we form atoms of
		 * the form "COL1 = COL2"
		 */
		for (Variable var : currentLevelVariables) {
			Set<QualifiedAttributeID> references = index.getColumnReferences(var);
			if (references.size() < 2) {
				// No need for equality
				continue;
			}
			Iterator<QualifiedAttributeID> referenceIterator = references.iterator();
			QualifiedAttributeID leftColumnReference = referenceIterator.next();
			while (referenceIterator.hasNext()) {
				QualifiedAttributeID rightColumnReference = referenceIterator.next();
				String equality = String.format("(%s = %s)",
						leftColumnReference.getSQLRendering(),
						rightColumnReference.getSQLRendering());
				equalities.add(equality);
				leftColumnReference = rightColumnReference;
			}
		}

		for (Function atom : atoms) {
			if (!atom.isDataFunction()) {
				continue;
			}
			for (int idx = 0; idx < atom.getArity(); idx++) {
				Term l = atom.getTerm(idx);
				if (l instanceof Constant) {
					String value = getSQLString(l, index, false);
					String columnReference = index
							.getColumnReference(atom, idx);
					equalities.add(String.format("(%s = %s)", columnReference,
							value));
				}
			}

		}
		return equalities;
	}

	// return variable SQL data type
	private int getVariableDataType(Term term, QueryAliasIndex idx) {

		if (term instanceof Function){
			Function f = (Function) term;
			if (f.isDataTypeFunction()) {
				Predicate p = f.getFunctionSymbol();
				COL_TYPE type = TYPE_FACTORY.getDatatype(p.getName()).get();
				return jdbcTypeMapper.getSQLType(type);
			}
			// Return varchar for unknown
			return Types.VARCHAR;
		}else if (term instanceof Variable){
			throw new RuntimeException("Cannot return the SQL type for: "
					+ term.toString());
		}
		/**
		 * Boolean constant
		 */
		else if (term.equals(TermConstants.FALSE)
				 || term.equals(TermConstants.TRUE)) {
			return Types.BOOLEAN;
		}

		return Types.VARCHAR;
	}

	private String getWHERE(List<Function> atoms, QueryAliasIndex index) {
		String conditions = getConditionsString(atoms, index, false, "");
		if (conditions.isEmpty()) {
			return "";
		}
		return "\nWHERE \n" + conditions;
	}

	/**
	 * produces the select clause of the sql query for the given CQIE
	 *
	 * @param query
	 *            the query
	 * @param castTypes
	 * @param termTypes
	 * @return the sql select clause
	 */
	private String getSelectClause(List<String> signature, CQIE query,
								   QueryAliasIndex index, boolean distinct, boolean isAns1,
								   List<COL_TYPE> castTypes, ImmutableList<Optional<TermType>> termTypes) {
		/*
		 * If the head has size 0 this is a boolean query.
		 */
		List<Term> headterms = query.getHead().getTerms();
		StringBuilder sb = new StringBuilder();

		sb.append("SELECT ");
		if (distinct && !distinctResultSet) {
			sb.append("DISTINCT ");
		}
		//Only for ASK
		if (headterms.size() == 0) {
			sb.append("'true' as x");
			return sb.toString();
		}

		Iterator<Term> hit = headterms.iterator();
		int hpos = 0;

		Iterator<COL_TYPE> castTypeIter = castTypes.iterator();
		Iterator<Optional<TermType>> termTypeIter = termTypes.iterator();

		/**
		 * Set that contains all the variable names created on the top query.
		 * It helps the dialect adapter to generate variable names according to its possible restrictions.
		 * Currently, this is needed for the Oracle adapter (max. length of 30 characters).
		 */
		Set<String> sqlVariableNames = new HashSet<>();

		while (hit.hasNext()) {
			Term ht = hit.next();

			/**
			 * Datatype for the main column (to which it is cast).
			 * Beware, it may defer the RDF datatype (the one of the type column).
			 *
			 * Why? Because most DBs (if not all) require the result table to have
			 * one datatype per column. If the sub-queries are producing results of different types,
			 * them there will be a difference between the type in the main column and the RDF one.
			 */
			COL_TYPE castType = castTypeIter.next();

			Optional<TermType> optionalTermType = termTypeIter.next();

			String varName;

			/*
			 * When isAns1 is true, we need to use the <code>signature</code>
			 * for the varName
			 */
			if (isAns1) {
				varName = signature.get(hpos);
			} else {
				varName = "v" + hpos;
			}

			String typeColumn = getTypeColumnForSELECT(ht, varName, index, sqlVariableNames, optionalTermType);
			String mainColumn = getMainColumnForSELECT(ht, varName, index, castType, sqlVariableNames);
			String langColumn = getLangColumnForSELECT(ht, varName, index, sqlVariableNames, optionalTermType);

			sb.append("\n   ");
			sb.append(typeColumn);
			sb.append(", ");
			sb.append(langColumn);
			sb.append(", ");
			sb.append(mainColumn);
			if (hit.hasNext()) {
				sb.append(", ");
			}
			hpos++;
		}
		return sb.toString();
	}

	private String getMainColumnForSELECT(Term ht, String signatureVarName,
										  QueryAliasIndex index, COL_TYPE castDataType,
										  Set<String> sqlVariableNames) {

		final String varName = sqladapter.nameTopVariable(signatureVarName, MAIN_COLUMN_SUFFIX, sqlVariableNames);
		sqlVariableNames.add(varName);

		String mainColumn;

		String mainTemplate = "%s AS %s";

		if (ht instanceof URIConstant) {
			URIConstant uc = (URIConstant) ht;
			mainColumn = sqladapter.getSQLLexicalFormString(uc.getURI().toString());
			/**
			 * TODO: we should not have to treat NULL as a special case! It is because this constant is currently
			 * a STRING!
			 */
		} else if (ht == TermConstants.NULL) {
			mainColumn = "NULL";
		} else if (ht instanceof ValueConstant) {
			mainColumn = getSQLLexicalForm((ValueConstant) ht);
		} else if (ht instanceof Variable) {
			Variable termVar = (Variable) ht;
			mainColumn = getSQLString(termVar, index, false);
		} else if (ht instanceof Function) {
			/*
			 * if it's a function we need to get the nested value if its a
			 * datatype function or we need to do the CONCAT if its URI(....).
			 */
			Function ov = (Function) ht;
			Predicate functionSymbol = ov.getFunctionSymbol();

			/*
			 * Adding the column(s) with the actual value(s)
			 */
			if (ov.isDataTypeFunction()) {
				/*
				 * Case where we have a typing function in the head (this is the
				 * case for all literal columns
				 */
				String termStr = null;
				int size = ov.getTerms().size();
				if ((functionSymbol instanceof Literal) || size > 2) {
					termStr = getSQLStringForTemplateFunction(ov, index);
				} else {
					Term term = ov.getTerms().get(0);
					if (term instanceof ValueConstant) {
						termStr = getSQLLexicalForm((ValueConstant) term);
					} else {
						termStr = getSQLString(term, index, false);
					}
				}
				mainColumn = termStr;

			}
			else if (functionSymbol instanceof URITemplatePredicate) {
				// New template based URI building functions
				mainColumn = getSQLStringForTemplateFunction(ov, index);
			}
            else if (functionSymbol instanceof BNodePredicate) {
				// New template based BNODE building functions
				mainColumn = getSQLStringForTemplateFunction(ov, index);
			}
			else if (ov.isOperation()) {
				mainColumn = getSQLString(ov, index, false);
			}
			else {
				throw new IllegalArgumentException(
						"Error generating SQL query. Found an invalid function during translation: "
								+ ov.toString());
			}
		} else {
			throw new RuntimeException("Cannot generate SELECT for term: "
					+ ht.toString());
		}

		/*
		 * If the we have a column we need to still CAST to VARCHAR
		 */
		if (mainColumn.charAt(0) != '\'' && mainColumn.charAt(0) != '(') {

			if (castDataType != null){

				mainColumn = sqladapter.sqlCast(mainColumn, jdbcTypeMapper.getSQLType(castDataType));
			}

			//int sqlType = getSQLTypeForTerm(ht,index );
//			
//			if(sqlType != Types.NULL){
//				mainColumn = sqladapter.sqlCast(mainColumn, sqlType);	
//			}


		}


		String format = String.format(mainTemplate, mainColumn, varName);

		return format;
	}

	private String getLangColumnForSELECT(Term ht, String signatureVarName, QueryAliasIndex index,
										  Set<String> sqlVariableNames, Optional<TermType> optionalTermType) {

        /**
         * Creates a variable name that fits to the restrictions of the SQL dialect.
         */
        String langVariableName = sqladapter.nameTopVariable(signatureVarName, LANG_SUFFIX, sqlVariableNames);
        sqlVariableNames.add(langVariableName);

		final String lang;

		if (ht instanceof Variable) {
			lang = getLangFromVariable((Variable) ht, index);
		}
		else {
			lang = optionalTermType
					.filter(t -> t.getColType() == LANG_STRING)
					.map(t -> t.getLanguageTagConstant()
								.map(tag -> "'" + tag.getFullString() + "'")
								.orElseGet(() -> t.getLanguageTagTerm()
										.map(tag -> getSQLString(tag, index, false))
										.orElseThrow(() -> new IllegalStateException(
												"Inconsistent term type: the language tag must be defined " +
														"for any LANG_STRING"))))
					.orElse("NULL");
		}
		return String.format(LANG_STR, lang, langVariableName);
    }

	/**
	 * Infers the type of a projected term.
	 *
	 * Note this type may differ from the one used for casting the main column (in some special cases).
	 * This type will appear as the RDF datatype.
	 *  @param projectedTerm
	 * @param signatureVarName Name of the variable
	 * @param index Used when the term correspond to a column name
	 * @param sqlVariableNames Used for creating non conflicting variable names (when they have to be shorten)  @return A string like "5 AS ageQuestType"
	 * @param optionalTermType
	 */
	private String getTypeColumnForSELECT(Term projectedTerm, String signatureVarName,
										  QueryAliasIndex index,
										  Set<String> sqlVariableNames, Optional<TermType> optionalTermType) {

		final String varName = sqladapter.nameTopVariable(signatureVarName, TYPE_SUFFIX, sqlVariableNames);
		sqlVariableNames.add(varName);

		final String typeString;
		if (projectedTerm instanceof Variable) {
			typeString = getTypeFromVariable((Variable) projectedTerm, index);
		}
		else {
			COL_TYPE colType = optionalTermType
					.map(TermType::getColType)
					/**
					 * By default, we apply the "most" general COL_TYPE
					 */
					.orElse(STRING);

			typeString = String.format("%d", colType.getQuestCode());
		}

		return String.format(TYPE_STR, typeString, varName);
	}

	/**
	 * Gets the type of a variable.
	 *
	 * Such variable does not hold this information, so we have to look
	 * at the database metadata.
	 *
	 *
	 * @param var
	 * @param index
	 * @return
	 */
	private String getTypeFromVariable(Variable var, QueryAliasIndex index) {

		return getNonMainColumnId(var, index, -2)
				.map(QualifiedAttributeID::getSQLRendering)
				/**
				 * By default, we assume that the variable is an IRI.
				 *
				 */
				.orElseGet(() -> String.format("%d", OBJECT.getQuestCode()));
	}

	private static String getLangFromVariable(Variable var, QueryAliasIndex index) {
		return getNonMainColumnId(var, index, -1)
				.map(QualifiedAttributeID::getSQLRendering)
				.orElse("NULL");
	}

	private static Optional<QualifiedAttributeID> getNonMainColumnId(Variable var, QueryAliasIndex index,
																	 int relativeIndexWrtMainColumn) {
		Set<QualifiedAttributeID> columnRefs = index.getColumnReferences(var);

		if (columnRefs == null || columnRefs.size() == 0) {
			throw new RuntimeException(
					"Unbound variable found in WHERE clause: " + var);
		}

		/**
		 * For each column reference corresponding to the variable.
		 *
		 * For instance, columnRef is `Qans4View`.`v1` .
		 */
		for (QualifiedAttributeID mainColumn : columnRefs) {
			RelationID relationId = mainColumn.getRelation();

			/**
			 * If the var is defined in a ViewDefinition, then there is a
			 * column for the type and we just need to refer to that column.
			 *
			 * For instance, tableColumnType becomes `Qans4View`.`v1QuestType` .
			 */
			Optional<RelationDefinition> optionalViewDefinition = index.getDefinition(relationId);

			if (optionalViewDefinition.isPresent()
					&& (optionalViewDefinition.get() instanceof ParserViewDefinition)) {
				ParserViewDefinition viewDefinition = (ParserViewDefinition) optionalViewDefinition.get();

				List<QualifiedAttributeID> columnIds = viewDefinition.getAttributes().stream()
						.map(Attribute::getQualifiedID)
						.collect(Collectors.toList());
				int mainColumnIndex = columnIds.indexOf(mainColumn) + 1;

				Attribute typeColumn = viewDefinition.getAttribute(mainColumnIndex + relativeIndexWrtMainColumn);
				return Optional.of(typeColumn.getQualifiedID());
			}
		}

		return Optional.empty();
	}

	public String getSQLStringForTemplateFunction(Function ov,
												  QueryAliasIndex index) {
		/*
		 * The first inner term determines the form of the result
		 */
		Term t = ov.getTerms().get(0);

		String literalValue = "";

		if (t instanceof ValueConstant || t instanceof BNode) {
			/*
			 * The function is actually a template. The first parameter is a
			 * string of the form http://.../.../ or empty "{}" with place holders of the form
			 * {}. The rest are variables or constants that should be put in
			 * place of the place holders. We need to tokenize and form the
			 * CONCAT
			 */
			if (t instanceof BNode) {
				//TODO: why getValue and not getName(). Change coming from v1.
				literalValue = ((BNode) t).getName();
			} else {
				literalValue = ((ValueConstant) t).getValue();
			}
			Predicate pred = ov.getFunctionSymbol();

			String template = trimLiteral(literalValue);

			String[] split = template.split("[{][}]");

			List<String> vex = new LinkedList<>();
			if (split.length > 0 && !split[0].isEmpty()) {
				vex.add(sqladapter.getSQLLexicalFormString(split[0]));
			}

			/*
			 * New we concat the rest of the function, note that if there is
			 * only 1 element there is nothing to concatenate
			 */
			if (ov.getTerms().size() > 1) {
				int size = ov.getTerms().size();
//				if (TYPE_FACTORY.isLiteral(pred)) {
//					size--;
//				}
				for (int termIndex = 1; termIndex < size; termIndex++) {
					Term currentTerm = ov.getTerms().get(termIndex);
					String repl = "";
					if (isStringColType(currentTerm, index)) {
						//empty place holders: the correct uri is in the column of DB no need to replace
						if(split.length == 0)
						{
							repl = getSQLString(currentTerm, index, false) ;
						}
						else
						{
							repl = replace1 + (getSQLString(currentTerm, index, false)) + replace2;
						}

					} else {
						if(split.length == 0)
						{
							repl = sqladapter.sqlCast(getSQLString(currentTerm, index, false), Types.VARCHAR) ;
						}
						else {
							repl = replace1 + sqladapter.sqlCast(getSQLString(currentTerm, index, false), Types.VARCHAR) + replace2;
						}
					}
					vex.add(repl);
					if (termIndex < split.length) {
						vex.add(sqladapter.getSQLLexicalFormString(split[termIndex]));
					}
				}
			}

			if (vex.size() == 1) {

				return vex.get(0);
			}
			String[] params = new String[vex.size()];
			int i = 0;
			for (String param : vex) {
				params[i] = param;
				i += 1;
			}
			return getStringConcatenation(params);

		} else if (t instanceof Variable) {
			/*
			 * The function is of the form uri(x), we need to simply return the
			 * value of X
			 */
			return getSQLString(((Variable) t), index, false);

		} else if (t instanceof URIConstant) {
			/*
			 * The function is of the form uri("http://some.uri/"), i.e., a
			 * concrete URI, we return the string representing that URI.
			 */
			URIConstant uc = (URIConstant) t;
			return sqladapter.getSQLLexicalFormString(uc.getURI());
		}
		/**
		 * Complex first argument: treats it as a string and ignore other arguments
		 */
		else {
			/*
			 * The function is for example of the form uri(CONCAT("string",x)),we simply return the value from the database.
			 */
			return getSQLString(t, index, false);
		}
	}

	// TODO: move to SQLAdapter
	private String getStringConcatenation(String[] params) {
		String toReturn = sqladapter.strConcat(params);
		if (sqladapter instanceof DB2SQLDialectAdapter) {
			/*
			 * A work around to handle DB2 (>9.1) issue SQL0134N: Improper use
			 * of a string column, host variable, constant, or function name.
			 * http
			 * ://publib.boulder.ibm.com/infocenter/db2luw/v9r5/index.jsp?topic
			 * =%2Fcom.ibm.db2.luw.messages.sql.doc%2Fdoc%2Fmsql00134n.html
			 */
			if (isDistinct || isOrderBy) {
				return sqladapter.sqlCast(toReturn, Types.VARCHAR);
			}
		}
		return toReturn;
	}

	private boolean isStringColType(Term term, QueryAliasIndex index) {
		if (term instanceof Function) {
			Function function = (Function) term;
			Predicate functionSymbol = function.getFunctionSymbol();
			if (functionSymbol instanceof URITemplatePredicate) {
				/*
				 * A URI function always returns a string, thus it is a string
				 * column type.
				 */
				return !hasIRIDictionary();
			} else {
				if (isUnary(function)) {
					if (functionSymbol.getName().equals("Count")) {
						return false;
					}
					/*
					 * Update the term with the parent term's first parameter.
					 * Note: this method is confusing :(
					 */
					term = function.getTerm(0);
					return isStringColType(term, index);
				}
			}
		} else if (term instanceof Variable) {
			Set<QualifiedAttributeID> viewdef = index
					.getColumnReferences((Variable) term);
			QualifiedAttributeID def = viewdef.iterator().next();

			RelationID relationId = def.getRelation();
			QuotedID colId = def.getAttribute();

			// Non-final TODO: understand
			String table = relationId.getTableName();

			if (relationId.getTableName().startsWith("QVIEW")) {
				Map<Function, RelationID> views = index.viewNames;
				for (Function func : views.keySet()) {
					RelationID knownViewId = views.get(func);
					if (knownViewId.equals(relationId)) {
						table = func.getFunctionSymbol().toString();
						break;
					}
				}
			}
			Collection<DatabaseRelationDefinition> tables = metadata.getDatabaseRelations();
			for (DatabaseRelationDefinition tabledef : tables) {
				if (tabledef.getID().getTableName().equals(table)) {
					List<Attribute> attr = tabledef.getAttributes();
					for (Attribute a : attr) {
						if (a.getID().equals(colId)) {
							switch (a.getType()) {
								case Types.VARCHAR:
								case Types.CHAR:
								case Types.LONGNVARCHAR:
								case Types.LONGVARCHAR:
								case Types.NVARCHAR:
								case Types.NCHAR:
									return true;
								default:
									return false;
							}
						}
					}
				}
			}
		}
		return false;
	}

	private boolean hasIRIDictionary() {
		return uriRefIds != null;
	}

	private static final Pattern pQuotes = Pattern.compile("[\"`\\['][^\\.]*[\"`\\]']");

	private static String trimLiteral(String string) {
		while (pQuotes.matcher(string).matches()) {
			string = string.substring(1, string.length() - 1);
		}
		return string;
	}

	/**
	 * Determines if it is a unary function.
	 */
	private boolean isUnary(Function fun) {
		return fun.getArity() == 1;
	}

	/**
	 * Determines if it is a binary function.
	 */
	private boolean isBinary(Function fun) {
		return fun.getArity() == 2;
	}

	/**
	 * Generates the SQL string that forms or retrieves the given term. The
	 * function takes as input either: a constant (value or URI), a variable, or
	 * a Function (i.e., uri(), eq(..), ISNULL(..), etc)).
	 * <p>
	 * If the input is a constant, it will return the SQL that generates the
	 * string representing that constant.
	 * <p>
	 * If its a variable, it returns the column references to the position where
	 * the variable first appears.
	 * <p>
	 * If its a function uri(..) it returns the SQL string concatenation that
	 * builds the result of uri(...)
	 * <p>
	 * If its a boolean comparison, it returns the corresponding SQL comparison.
	 */
	public String getSQLString(Term term, QueryAliasIndex index,
							   boolean useBrackets) {
		if (term == null) {
			return "";
		}
		if (term instanceof ValueConstant) {
			ValueConstant ct = (ValueConstant) term;
			if (hasIRIDictionary()) {
				if (ct.getType() == OBJECT || ct.getType() == STRING) {
					int id = getUriid(ct.getValue());
					if (id >= 0)
						//return jdbcutil.getSQLLexicalForm(String.valueOf(id));
						return String.valueOf(id);
				}
			}
			return getSQLLexicalForm(ct);
		} else if (term instanceof URIConstant) {
			if (hasIRIDictionary()) {
				String uri = term.toString();
				int id = getUriid(uri);
				return sqladapter.getSQLLexicalFormString(String.valueOf(id));
			}
			URIConstant uc = (URIConstant) term;
			return sqladapter.getSQLLexicalFormString(uc.toString());
		} else if (term instanceof Variable) {
			Variable var = (Variable) term;
			Set<QualifiedAttributeID> posList = index.getColumnReferences(var);
			if (posList == null || posList.size() == 0) {
				throw new RuntimeException(
						"Unbound variable found in WHERE clause: " + term);
			}
			return posList.iterator().next().getSQLRendering();
		}

		/* If its not constant, or variable its a function */

		Function function = (Function) term;
		Predicate functionSymbol = function.getFunctionSymbol();
		int size = function.getTerms().size();

		if (function.isDataTypeFunction()) {
			if (functionSymbol.getType(0) == UNSUPPORTED) {
				throw new RuntimeException("Unsupported type in the query: "
						+ function);
			}
			if (size == 1) {
				// atoms of the form integer(x)
				Term term1 = function.getTerm(0);
				return getSQLString(term1, index, false);
			} else {
				return getSQLStringForTemplateFunction(function, index);
			}
		}
		else if (operations.containsKey(functionSymbol)) {
			// atoms of the form EQ(x,y)
			String expressionFormat = operations.get(functionSymbol);
			if (function.getArity() == 1) {
				Term term1 = function.getTerm(0);
				// for unary functions, e.g., NOT, IS NULL, IS NOT NULL
				// also added for IS TRUE
				if (functionSymbol == ExpressionOperation.IS_TRUE) {
					// find data type of term and evaluate accordingly
					String column = getSQLString(term1, index, false);
					int type = getVariableDataType(term1, index);
					if (type == Types.INTEGER || type == Types.BIGINT || type == Types.DOUBLE || type == Types.FLOAT)
						return String.format("%s > 0", column);
					else if (type == Types.BOOLEAN)
						return String.format("%s", column);
					else if (type == Types.VARCHAR)
						return String.format("LENGTH(%s) > 0", column);
					return "1";
				}
				String op = getSQLString(term1, index, true);
				return String.format(expressionFormat, op);
			}
			else if (function.getArity() == 2) {
				// for binary functions, e.g., AND, OR, EQ, NEQ, GT etc.
				String leftOp = getSQLString(function.getTerm(0), index, true);
				String rightOp = getSQLString(function.getTerm(1), index, true);
				String result = String.format(expressionFormat, leftOp, rightOp);
				if (useBrackets)
					return String.format("(%s)", result);
				else
					return result;
			}
			else if (function.getArity() == 0) {
				return expressionFormat;
			}
			else
				throw new RuntimeException("Cannot translate boolean function: " + functionSymbol);
		}
		else if (functionSymbol == ExpressionOperation.REGEX) {
					boolean caseinSensitive = false;
					boolean multiLine = false;
					boolean dotAllMode = false;
					if (function.getArity() == 3) {
						if (function.getTerm(2).toString().contains("i")) {
							caseinSensitive = true;
						}
						if (function.getTerm(2).toString().contains("m")) {
							multiLine = true;
						}
						if (function.getTerm(2).toString().contains("s")) {
							dotAllMode = true;
						}
					}
					Term p1 = function.getTerm(0);
					Term p2 = function.getTerm(1);

					String column = getSQLString(p1, index, false);
					String pattern = getSQLString(p2, index, false);
					return sqladapter.sqlRegex(column, pattern, caseinSensitive, multiLine, dotAllMode);
				}
		else if (functionSymbol == ExpressionOperation.SPARQL_LANG) {
			Variable var = (Variable) function.getTerm(0);
			Set<QualifiedAttributeID> posList = index.getColumnReferences(var);

			if (posList == null || posList.size() == 0) {
				throw new RuntimeException(
						"Unbound variable found in WHERE clause: " + term);
			}

			String langC = posList.iterator().next().getSQLRendering();
			String langColumn = langC.replaceAll("`$", "Lang`");
			return langColumn;


			/**
			 * TODO: replace by a switch
			 */
		}else {
			if (functionSymbol == ExpressionOperation.QUEST_CAST) {
				String columnName = getSQLString(function.getTerm(0), index,
						false);
				String datatype = ((Constant) function.getTerm(1)).getValue();
				int sqlDatatype = -1;
				if (datatype.equals(XMLSchema.STRING.stringValue())){
					sqlDatatype = Types.VARCHAR;
				}
				if (isStringColType(function, index)) {
					return columnName;
				} else {
					return sqladapter.sqlCast(columnName, sqlDatatype);
				}
			} else if (functionSymbol == ExpressionOperation.SPARQL_STR) {
				String columnName = getSQLString(function.getTerm(0), index,
						false);
				if (isStringColType(function, index)) {
					return columnName;
				} else {
					return sqladapter.sqlCast(columnName, Types.VARCHAR);
				}
			}else if (functionSymbol == ExpressionOperation.REPLACE) {
                String orig = getSQLString(function.getTerm(0), index, false);
                String out_str = getSQLString(function.getTerm(1), index, false);
                String in_str = getSQLString(function.getTerm(2), index, false);
                String result = sqladapter.strReplace(orig, out_str, in_str);
				// TODO: handle flags
                return result;
            }
            else if (functionSymbol == ExpressionOperation.CONCAT) {
                String left = getSQLString(function.getTerm(0), index, false);
                String right = getSQLString(function.getTerm(1), index, false);
                String result = sqladapter.strConcat(new String[]{left, right});
                return result;
            }
			else if (functionSymbol == ExpressionOperation.STRLEN) {
				String literal = getSQLString(function.getTerm(0), index, false);
				String result = sqladapter.strLength(literal);
				return result;
			}
			else if (functionSymbol == ExpressionOperation.YEAR) {
				String literal = getSQLString(function.getTerm(0), index, false);
				String result = sqladapter.dateYear(literal);
				return result;
			}
			else if (functionSymbol == ExpressionOperation.MINUTES) {
				String literal = getSQLString(function.getTerm(0), index, false);
				String result = sqladapter.dateMinutes(literal);
				return result;
			}
			else if (functionSymbol == ExpressionOperation.DAY) {
				String literal = getSQLString(function.getTerm(0), index, false);
				String result = sqladapter.dateDay(literal);
				return result;
			}
			else if (functionSymbol == ExpressionOperation.MONTH) {
				String literal = getSQLString(function.getTerm(0), index, false);
				String result = sqladapter.dateMonth(literal);
				return result;
			}
			else if (functionSymbol == ExpressionOperation.SECONDS) {
				String literal = getSQLString(function.getTerm(0), index, false);
				String result = sqladapter.dateSeconds(literal);
				return result;
			}
			else if (functionSymbol == ExpressionOperation.HOURS) {
				String literal = getSQLString(function.getTerm(0), index, false);
				String result = sqladapter.dateHours(literal);
				return result;
			}
			else if (functionSymbol == ExpressionOperation.TZ) {
				String literal = getSQLString(function.getTerm(0), index, false);
				String result = sqladapter.dateTZ(literal);
				return result;
			}
			else if (functionSymbol == ExpressionOperation.ENCODE_FOR_URI) {
				String literal = getSQLString(function.getTerm(0), index, false);
				String result = sqladapter.strEncodeForUri(literal);
				return result;
			}
			else if (functionSymbol == ExpressionOperation.UCASE) {
				String literal = getSQLString(function.getTerm(0), index, false);
				String result = sqladapter.strUcase(literal);
				return result;
			}
			else if (functionSymbol == ExpressionOperation.MD5) {
				String literal = getSQLString(function.getTerm(0), index, false);
				String result = sqladapter.MD5(literal);
				return result;
			}
			else if (functionSymbol == ExpressionOperation.SHA1) {
				String literal = getSQLString(function.getTerm(0), index, false);
				String result = sqladapter.SHA1(literal);
				return result;
			}
			else if (functionSymbol == ExpressionOperation.SHA256) {
				String literal = getSQLString(function.getTerm(0), index, false);
				String result = sqladapter.SHA256(literal);
				return result;
			}
			else if (functionSymbol == ExpressionOperation.SHA512) {
				String literal = getSQLString(function.getTerm(0), index, false);
				String result = sqladapter.SHA512(literal); //TODO FIX
				return result;
			}
			else if (functionSymbol == ExpressionOperation.LCASE) {
				String literal = getSQLString(function.getTerm(0), index, false);
				String result = sqladapter.strLcase(literal);
				return result;
			}
			else if (functionSymbol == ExpressionOperation.SUBSTR2) {
				String string = getSQLString(function.getTerm(0), index, false);
				String start = getSQLString(function.getTerm(1), index, false);
				return sqladapter.strSubstr(string, start);
			}
			else if (functionSymbol == ExpressionOperation.SUBSTR3) {
				String string = getSQLString(function.getTerm(0), index, false);
				String start = getSQLString(function.getTerm(1), index, false);
				String end = getSQLString(function.getTerm(2), index, false);
				String result = sqladapter.strSubstr(string, start, end);

				return result;
			}
			else if (functionSymbol == ExpressionOperation.STRBEFORE) {
				String string = getSQLString(function.getTerm(0), index, false);
				String before = getSQLString(function.getTerm(1), index, false);
				String result = sqladapter.strBefore(string, before);
				return result;
			}
			else if (functionSymbol == ExpressionOperation.STRAFTER) {
				String string = getSQLString(function.getTerm(0), index, false);
				String after = getSQLString(function.getTerm(1), index, false);
				String result = sqladapter.strAfter(string, after);
				return result;
			}
			else if (functionSymbol == ExpressionOperation.COUNT) {
				if (function.getTerm(0).toString().equals("*")) {
					return "COUNT(*)";
				}
				String columnName = getSQLString(function.getTerm(0), index, false);
				//havingCond = true;
				return "COUNT(" + columnName + ")";
			} else if (functionSymbol == ExpressionOperation.AVG) {
				String columnName = getSQLString(function.getTerm(0), index, false);
				//havingCond = true;
				return "AVG(" + columnName + ")";
			} else if (functionSymbol == ExpressionOperation.SUM) {
				String columnName = getSQLString(function.getTerm(0), index, false);
				//havingCond = true;
				return "SUM(" + columnName + ")";
			} else if (functionSymbol.equals(ExpressionOperation.IF_ELSE_NULL)) {
				String condition = getSQLString(function.getTerm(0), index, false);
				String value = getSQLString(function.getTerm(1), index, false);
				return sqladapter.ifElseNull(condition, value);
			}
		}

		/*
		 * The atom must be of the form uri("...", x, y)
		 */
		if (functionSymbol instanceof URITemplatePredicate
				|| functionSymbol instanceof BNodePredicate) {
			return getSQLStringForTemplateFunction(function, index);
		} else {
			throw new RuntimeException("Unexpected function in the query: "
					+ functionSymbol);
		}
	}

	/***
	 * Returns the valid SQL lexical form of rdf literals based on the current
	 * database and the datatype specified in the function predicate.
	 *
	 * <p>
	 * For example, if the function is xsd:boolean, and the current database is
	 * H2, the SQL lexical form would be for "true" "TRUE" (or any combination
	 * of lower and upper case) or "1" is always
	 *
	 * @param constant
	 * @return
	 */
	private String getSQLLexicalForm(ValueConstant constant) {
		switch (constant.getType()) {
			case BNODE:
			case OBJECT:
			case STRING:
				return sqladapter.getSQLLexicalFormString(constant.getValue());
			case BOOLEAN:
				boolean v = XsdDatatypeConverter.parseXsdBoolean(constant.getValue());
				return sqladapter.getSQLLexicalFormBoolean(v);
			case DATETIME:
				return sqladapter.getSQLLexicalFormDatetime(constant.getValue());
			case DATETIME_STAMP:
				return sqladapter.getSQLLexicalFormDatetimeStamp(constant.getValue());
			case DECIMAL:
			case DOUBLE:
			case INTEGER:
			case LONG:
			case FLOAT:
			case NON_POSITIVE_INTEGER:
			case INT:
			case UNSIGNED_INT:
			case NEGATIVE_INTEGER:
			case POSITIVE_INTEGER:
			case NON_NEGATIVE_INTEGER:
				return constant.getValue();
			case NULL:
				return "NULL";
			default:
				return "'" + constant.getValue() + "'";
		}
	}

	/***
	 * We look for the ID in the list of IDs, if its not there, we return -2,
	 * which we know will never appear on the DB. This is correct because if a
	 * constant appears in a query, and that constant was never inserted in the
	 * DB, the query must be empty (that atom), by putting -2 as id, we will
	 * enforce that.
	 *
	 * @param uri
	 * @return
	 */
	private int getUriid(String uri) {

		Integer id = uriRefIds.getId(uri);
		if (id != null)
			return id;
		return -2;

	}

	/**
	 * Utility class to resolve "database" atoms to view definitions ready to be
	 * used in a FROM clause, and variables, to column references defined over
	 * the existing view definitions of a query.
	 */
	public class QueryAliasIndex {

		final Map<Function, RelationID> viewNames = new HashMap<>();
		final Map<Function, RelationDefinition> dataDefinitions = new HashMap<>();
		final Map<RelationID, RelationDefinition> dataDefinitionsById = new HashMap<>();
		final Map<Variable, Set<QualifiedAttributeID>> columnReferences = new HashMap<>();

		int dataTableCount = 0;
		boolean isEmpty = false;

		public QueryAliasIndex(CQIE query, Map<Predicate, ParserViewDefinition> subQueryDefinitions) {
			List<Function> body = query.getBody();
			generateViews(body, subQueryDefinitions);
		}

		private void generateViews(List<Function> atoms, Map<Predicate, ParserViewDefinition> subQueryDefinitions) {
			for (Function atom : atoms) {
				/*
				 * This will be called recursively if necessary
				 */
				generateViewsIndexVariables(atom, subQueryDefinitions);
			}
		}

		/***
		 * We assiciate each atom to a view definition. This will be
		 * <p>
		 * "tablename" as "viewX" or
		 * <p>
		 * (some nested sql view) as "viewX"
		 *
		 * <p>
		 * View definitions are only done for data atoms. Join/LeftJoin and
		 * boolean atoms are not associated to view definitions.
		 *
		 * @param atom
		 * @param subQueryDefinitions
		 */
		private void generateViewsIndexVariables(Function atom,
												 Map<Predicate, ParserViewDefinition> subQueryDefinitions) {
			if (atom.isOperation()) {
				return;
			} else if (atom.isAlgebraFunction()){
				List<Term> lit = atom.getTerms();
				for (Term subatom : lit) {
					if (subatom instanceof Function) {
						generateViewsIndexVariables((Function) subatom, subQueryDefinitions);
					}
				}
			}

			Predicate predicate = atom.getFunctionSymbol();
			RelationID tableId = Relation2Predicate.createRelationFromPredicateName(metadata.getQuotedIDFactory(),
					predicate);
			RelationDefinition def = metadata.getRelation(tableId);


			final RelationID relationId;

			if (def == null) {
				/*
				 * There is no definition for this atom, its not a database
				 * predicate. We check if it is an ans predicate and it has a
				 * view:
				 */
				def = subQueryDefinitions.get(predicate);
				if (def == null) {
					isEmpty = true;
					return;
				} else {
					RelationID viewId = def.getID();
					viewNames.put(atom, viewId);
					relationId = viewId;
				}
			} else {
				relationId = tableId;

				String suffix = VIEW_SUFFIX + String.valueOf(dataTableCount);

				String safePredicateName = escapeName(predicate.getName());
				String simpleViewName = sqladapter.nameView(VIEW_PREFIX, safePredicateName, suffix, viewNames.values());
				viewNames.put(atom, metadata.getQuotedIDFactory().createRelationID(null, simpleViewName));
			}
			dataTableCount++;
			dataDefinitions.put(atom, def);
			dataDefinitionsById.put(relationId, def);

			indexVariables(atom);
		}

		private void indexVariables(Function atom) {
			RelationDefinition def = dataDefinitions.get(atom);
			RelationID viewName = viewNames.get(atom);

			for (int index = 0; index < atom.getTerms().size(); index++) {
				Term term = atom.getTerms().get(index);

				if (term instanceof Variable) {

					Set<QualifiedAttributeID> references = columnReferences.get(term);
					if (references == null) {
						references = new LinkedHashSet<>();
						columnReferences.put((Variable) term, references);
					}

					/*
					 * the index of attributes of the definition starts from 1
					 */
					Attribute column;

					if (ruleIndex.containsKey(atom.getFunctionSymbol())) {
						// If I am here it means that it is not a database table
						// but a view from an Ans predicate
						int attPos = 3 * (index + 1);
						column = def.getAttribute(attPos);
					} else {
						column = def.getAttribute(index + 1);
					}

					QualifiedAttributeID qualifiedId = new QualifiedAttributeID(viewName, column.getID());
					references.add(qualifiedId);
				}

			}
		}

		/***
		 * Returns all the column aliases that correspond to this variable,
		 * across all the DATA atoms in the query (not algebra operators or
		 * boolean conditions.
		 *
		 * @param var
		 *            The variable we want the referenced columns.
		 */
		public Set<QualifiedAttributeID> getColumnReferences(Variable var) {
			return columnReferences.get(var);
		}

		/***
		 * Generates the view definition, i.e., "tablename viewname".
		 */
		public String getViewDefinition(Function atom) {
			/**
			 * Normal case
			 */
			RelationDefinition def = dataDefinitions.get(atom);
			if (def != null) {
				if (def instanceof DatabaseRelationDefinition) {
					return sqladapter.sqlTableName(dataDefinitions.get(atom).getID().getSQLRendering(),
							viewNames.get(atom).getSQLRendering());
				}
				else if (def instanceof ParserViewDefinition) {
					return String.format("(%s) %s", ((ParserViewDefinition) def).getStatement(),
							viewNames.get(atom).getSQLRendering());
				}
				throw new RuntimeException("Impossible to get data definition for: " + atom + ", type: " + def);
			}

			/**
			 * Special case of nullary atoms
			 */
			else if(atom.getArity() == 0){
				return "(" + sqladapter.getDummyTable() + ") tdummy";
			}

			/**
			 * Special case.
			 * For atoms nested in a LJ.
			 *
			 * TODO: unify with the normal case?
			 */
			else {
				// Should be an ans atom.
				Predicate pred = atom.getFunctionSymbol();
				String view = sqlAnsViewMap.get(pred);
				if (view != null) {
					// TODO: check if it is correct not to consider other view names.
					final String viewName = sqladapter.sqlQuote(sqladapter.nameView(VIEW_PREFIX, pred.getName(),
							VIEW_ANS_SUFFIX, ImmutableSet.of()));
					String formatView = String.format("(%s) %s", view, viewName);
					return formatView;
				}
				throw new RuntimeException(
						"Impossible to get data definition for: " + atom
								+ ", type: " + def);
			}
		}

		public Optional<RelationDefinition> getDefinition(RelationID relationId) {
			return Optional.ofNullable(dataDefinitionsById.get(relationId));
		}

		public String getColumnReference(Function atom, int column) {
			RelationID viewName = viewNames.get(atom);
			RelationDefinition def = dataDefinitions.get(atom);
			QuotedID columnname = def.getAttribute(column + 1).getID(); // indexes from 1
			return new QualifiedAttributeID(viewName, columnname).getSQLRendering();
		}
	}
}
