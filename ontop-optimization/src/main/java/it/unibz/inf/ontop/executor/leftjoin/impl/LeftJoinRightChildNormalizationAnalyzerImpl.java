package it.unibz.inf.ontop.executor.leftjoin.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import it.unibz.inf.ontop.exception.MinorOntopInternalBugException;
import it.unibz.inf.ontop.executor.leftjoin.LeftJoinRightChildNormalizationAnalyzer;
import it.unibz.inf.ontop.model.*;
import it.unibz.inf.ontop.model.impl.ImmutabilityTools;
import it.unibz.inf.ontop.pivotalrepr.DataNode;
import it.unibz.inf.ontop.sql.DatabaseRelationDefinition;
import it.unibz.inf.ontop.sql.ForeignKeyConstraint;
import it.unibz.inf.ontop.sql.UniqueConstraint;
import it.unibz.inf.ontop.utils.ImmutableCollectors;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static it.unibz.inf.ontop.model.ExpressionOperation.EQ;
import static it.unibz.inf.ontop.model.impl.OntopModelSingletons.DATA_FACTORY;

@Singleton
public class LeftJoinRightChildNormalizationAnalyzerImpl implements LeftJoinRightChildNormalizationAnalyzer {

    @Inject
    private LeftJoinRightChildNormalizationAnalyzerImpl() {
    }

    @Override
    public LeftJoinRightChildNormalizationAnalysis analyze(DataNode leftDataNode, DataNode rightDataNode, DBMetadata dbMetadata,
                                                           VariableGenerator variableGenerator) {
        DataAtom leftProjectionAtom = leftDataNode.getProjectionAtom();
        DataAtom rightProjectionAtom = rightDataNode.getProjectionAtom();

        ImmutableList<? extends VariableOrGroundTerm> leftArguments = leftProjectionAtom.getArguments();
        ImmutableList<? extends VariableOrGroundTerm> rightArguments = rightProjectionAtom.getArguments();

        Optional<DatabaseRelationDefinition> optionalLeftRelation = dbMetadata.getDatabaseRelationByPredicate(
                leftProjectionAtom.getPredicate());
        Optional<DatabaseRelationDefinition> optionalRightRelation = dbMetadata.getDatabaseRelationByPredicate(
                rightProjectionAtom.getPredicate());

        if (!(optionalLeftRelation.isPresent() && optionalRightRelation.isPresent())) {
            // TODO: print a warning
            return new LeftJoinRightChildNormalizationAnalysisImpl(false);
        }

        DatabaseRelationDefinition leftRelation = optionalLeftRelation.get();
        DatabaseRelationDefinition rightRelation = optionalRightRelation.get();

        ImmutableList<UniqueConstraint> matchedUCs = leftRelation.equals(rightRelation)
                ? extractMatchedUCs(leftRelation, leftArguments, rightArguments)
                : ImmutableList.of();

        ImmutableList<ForeignKeyConstraint> matchedFKs = extractMatchedFKs(leftRelation, rightRelation, leftArguments,
                rightArguments);

        if (matchedUCs.isEmpty() && matchedFKs.isEmpty()) {
            return new LeftJoinRightChildNormalizationAnalysisImpl(false);
        }

        ImmutableSet<Integer> nonMatchedRightAttributeIndexes = extractNonMatchedRightAttributeIndexes(matchedUCs,
                matchedFKs, rightArguments.size());
        ImmutableList<Integer> conflictingRightArgumentIndexes = nonMatchedRightAttributeIndexes.stream()
                .filter(i -> isRightArgumentConflicting(i, leftArguments, rightArguments, nonMatchedRightAttributeIndexes))
                .collect(ImmutableCollectors.toList());

        if (!conflictingRightArgumentIndexes.isEmpty()) {
            DataNode newRightDataNode = rightDataNode.newAtom(computeNewRightAtom(rightProjectionAtom.getPredicate(),
                    rightArguments, conflictingRightArgumentIndexes, variableGenerator));
            ImmutableExpression newExpression = computeExpression(rightArguments,
                    newRightDataNode.getProjectionAtom().getArguments());

            return new LeftJoinRightChildNormalizationAnalysisImpl(newRightDataNode, newExpression);
        }
        else {
            return new LeftJoinRightChildNormalizationAnalysisImpl(true);
        }
    }

    private ImmutableList<UniqueConstraint> extractMatchedUCs(DatabaseRelationDefinition relation,
                                                              ImmutableList<? extends VariableOrGroundTerm> leftArguments,
                                                              ImmutableList<? extends VariableOrGroundTerm> rightArguments) {
        return relation.getUniqueConstraints().stream()
                .filter(uc -> isUcMatching(uc, leftArguments, rightArguments))
                .collect(ImmutableCollectors.toList());
    }

    private boolean isUcMatching(UniqueConstraint uniqueConstraint,
                                 ImmutableList<? extends VariableOrGroundTerm> leftArguments,
                                 ImmutableList<? extends VariableOrGroundTerm> rightArguments) {
        return uniqueConstraint.getAttributes().stream()
                .allMatch(a -> leftArguments.get(a.getIndex() -1)
                        .equals(rightArguments.get(a.getIndex() - 1)));
    }

    private ImmutableList<ForeignKeyConstraint> extractMatchedFKs(DatabaseRelationDefinition leftRelation,
                                                                  DatabaseRelationDefinition rightRelation,
                                                                  ImmutableList<? extends VariableOrGroundTerm> leftArguments,
                                                                  ImmutableList<? extends VariableOrGroundTerm> rightArguments) {
        return leftRelation.getForeignKeys().stream()
                .filter(fk -> fk.getReferencedRelation().equals(rightRelation))
                .filter(fk -> isFkMatching(fk, leftArguments, rightArguments))
                .collect(ImmutableCollectors.toList());
    }

    private boolean isFkMatching(ForeignKeyConstraint foreignKey,
                                 ImmutableList<? extends VariableOrGroundTerm> leftArguments,
                                 ImmutableList<? extends VariableOrGroundTerm> rightArguments) {
        return foreignKey.getComponents().stream()
                .allMatch(c -> leftArguments.get(c.getAttribute().getIndex() - 1)
                        .equals(rightArguments.get(c.getReference().getIndex() - 1)));
    }

    private ImmutableSet<Integer> extractNonMatchedRightAttributeIndexes(ImmutableList<UniqueConstraint> matchedUCs,
                                                                          ImmutableList<ForeignKeyConstraint> matchedFKs,
                                                                          int arity) {
        return IntStream.range(0, arity)
                .filter(i -> (matchedUCs.stream()
                        .noneMatch(uc ->
                                uc.getAttributes().stream()
                                        .anyMatch(a -> a.getIndex() == (i + 1)))))
                .filter(i -> (matchedFKs.stream()
                        .noneMatch(fk ->
                                fk.getComponents().stream()
                                        .anyMatch(c -> c.getReference().getIndex() == (i + 1)))))
                .boxed()
                .collect(ImmutableCollectors.toSet());
    }

    private boolean isRightArgumentConflicting(int rightArgumentIndex, ImmutableList<? extends VariableOrGroundTerm> leftArguments,
                                               ImmutableList<? extends VariableOrGroundTerm> rightArguments,
                                               ImmutableSet<Integer> nonMatchedRightAttributeIndexes) {
        VariableOrGroundTerm rightArgument = rightArguments.get(rightArgumentIndex);
        /*
         * Ground term -> pulled out as an equality
         */
        if (rightArgument instanceof GroundTerm)
            return true;
        Variable rightVariable = (Variable) rightArgument;

        /*
         * Is conflicting if the variable occurs in the left atom or occurs more than once in the right atom.
         */
        if (leftArguments.contains(rightVariable))
            return true;
        return IntStream.range(0, rightArguments.size())
                // In case of an equality between two nonMatchedRightAttributeIndexes: count it once
                // (thanks to this order relation)
                .filter(i -> (i < rightArgumentIndex) || (!nonMatchedRightAttributeIndexes.contains(i)))
                .anyMatch(i -> rightArguments.get(i).equals(rightVariable));
    }

    private DataAtom computeNewRightAtom(AtomPredicate predicate, ImmutableList<? extends VariableOrGroundTerm> rightArguments,
                                         ImmutableList<Integer> conflictingRightArgumentIndexes, VariableGenerator variableGenerator) {
        ImmutableList<VariableOrGroundTerm> newArguments = IntStream.range(0, rightArguments.size())
                .boxed()
                .map(i -> conflictingRightArgumentIndexes.contains(i)
                        ? variableGenerator.generateNewVariable()
                        : rightArguments.get(i))
                .collect(ImmutableCollectors.toList());

        return DATA_FACTORY.getDataAtom(predicate, newArguments);
    }

    private ImmutableExpression computeExpression(ImmutableList<? extends VariableOrGroundTerm> formerRightArguments,
                                                  ImmutableList<? extends VariableOrGroundTerm> newRightArguments) {
        Stream<ImmutableExpression> expressions = IntStream.range(0, formerRightArguments.size())
                .filter(i -> !formerRightArguments.get(i).equals(newRightArguments.get(i)))
                .boxed()
                .map(i -> DATA_FACTORY.getImmutableExpression(EQ, newRightArguments.get(i), formerRightArguments.get(i)));

        return ImmutabilityTools.foldBooleanExpressions(expressions)
                .orElseThrow(() -> new MinorOntopInternalBugException("A boolean expression was expected"));
    }



    public static class LeftJoinRightChildNormalizationAnalysisImpl implements LeftJoinRightChildNormalizationAnalysis {

        @Nullable
        private final DataNode newRightDataNode;
        @Nullable
        private final ImmutableExpression expression;
        private final boolean isMatchingAConstraint;

        private LeftJoinRightChildNormalizationAnalysisImpl(DataNode newRightDataNode, ImmutableExpression expression) {
            this.newRightDataNode = newRightDataNode;
            this.expression = expression;
            this.isMatchingAConstraint = true;
        }

        private LeftJoinRightChildNormalizationAnalysisImpl(boolean isMatchingAConstraint) {
            this.newRightDataNode = null;
            this.expression = null;
            this.isMatchingAConstraint = isMatchingAConstraint;
        }

        @Override
        public boolean isMatchingAConstraint() {
            return isMatchingAConstraint;
        }

        @Override
        public Optional<DataNode> getProposedRightDataNode() {
            return Optional.ofNullable(newRightDataNode);
        }

        @Override
        public Optional<ImmutableExpression> getAdditionalExpression() {
            return Optional.ofNullable(expression);
        }
    }


}