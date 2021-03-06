package it.unibz.inf.ontop.injection.impl;

import com.google.common.collect.ImmutableList;
import com.google.inject.Module;
import it.unibz.inf.ontop.evaluator.TermNullabilityEvaluator;
import it.unibz.inf.ontop.injection.OntopModelConfiguration;
import it.unibz.inf.ontop.injection.IntermediateQueryFactory;
import it.unibz.inf.ontop.injection.QueryTransformerFactory;
import it.unibz.inf.ontop.iq.node.*;
import it.unibz.inf.ontop.iq.tools.VariableDefinitionExtractor;
import it.unibz.inf.ontop.model.type.TypeFactory;
import it.unibz.inf.ontop.model.term.TermFactory;
import it.unibz.inf.ontop.iq.*;
import it.unibz.inf.ontop.iq.transform.FilterNullableVariableQueryTransformer;
import it.unibz.inf.ontop.iq.transform.QueryRenamer;
import it.unibz.inf.ontop.iq.validation.IntermediateQueryValidator;

import static it.unibz.inf.ontop.model.OntopModelSingletons.*;

public class OntopModelModule extends OntopAbstractModule {

    protected OntopModelModule(OntopModelConfiguration configuration) {
        super(configuration.getSettings());
    }

    @Override
    protected void configure() {
        configureCoreConfiguration();

        // Core factories: Too central to be overloaded from the properties
        bind(TypeFactory.class).toInstance(TYPE_FACTORY);
        bind(TermFactory.class).toInstance(TERM_FACTORY);

        bindFromSettings(IntermediateQueryValidator.class);
        bindFromSettings(TermNullabilityEvaluator.class);
        bindFromSettings(FilterNullableVariableQueryTransformer.class);
        bindFromSettings(VariableDefinitionExtractor.class);

        Module iqFactoryModule = buildFactory(ImmutableList.of(
                IntermediateQueryBuilder.class,
                ConstructionNode.class,
                UnionNode.class,
                InnerJoinNode.class,
                LeftJoinNode.class,
                FilterNode.class,
                ExtensionalDataNode.class,
                IntensionalDataNode.class,
                EmptyNode.class,
                TrueNode.class
                ),
                IntermediateQueryFactory.class);
        install(iqFactoryModule);

        Module queryTransformerModule = buildFactory(ImmutableList.of(
                QueryRenamer.class),
                QueryTransformerFactory.class);
        install(queryTransformerModule);
    }
}
