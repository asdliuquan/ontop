package it.unibz.inf.ontop.answering.reformulation.rewriting.impl;

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

import it.unibz.inf.ontop.answering.reformulation.rewriting.ExistentialQueryRewriter;
import it.unibz.inf.ontop.datalog.CQIE;
import it.unibz.inf.ontop.datalog.DatalogProgram;
import it.unibz.inf.ontop.model.term.Function;
import it.unibz.inf.ontop.model.term.Term;
import it.unibz.inf.ontop.model.term.functionsymbol.Predicate;
import it.unibz.inf.ontop.model.term.Variable;
import it.unibz.inf.ontop.spec.ontology.ClassExpression;
import it.unibz.inf.ontop.spec.ontology.DataPropertyExpression;
import it.unibz.inf.ontop.spec.ontology.ImmutableOntologyVocabulary;
import it.unibz.inf.ontop.spec.ontology.OClass;
import it.unibz.inf.ontop.spec.ontology.ObjectPropertyExpression;
import it.unibz.inf.ontop.spec.ontology.ObjectSomeValuesFrom;
import it.unibz.inf.ontop.spec.ontology.DataSomeValuesFrom;
import it.unibz.inf.ontop.datalog.impl.CQCUtilities;
import it.unibz.inf.ontop.datalog.impl.CQContainmentCheckUnderLIDs;
import it.unibz.inf.ontop.datalog.LinearInclusionDependencies;
import it.unibz.inf.ontop.spec.ontology.TBoxReasoner;
import it.unibz.inf.ontop.answering.reformulation.rewriting.impl.QueryConnectedComponent.Edge;
import it.unibz.inf.ontop.answering.reformulation.rewriting.impl.QueryConnectedComponent.Loop;
import it.unibz.inf.ontop.answering.reformulation.rewriting.impl.TreeWitnessSet.CompatibleTreeWitnessSetIterator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static it.unibz.inf.ontop.model.OntopModelSingletons.DATALOG_FACTORY;
import static it.unibz.inf.ontop.model.OntopModelSingletons.TERM_FACTORY;


/**
 * 
 */

public class TreeWitnessRewriter implements ExistentialQueryRewriter {

	private static final Logger log = LoggerFactory.getLogger(TreeWitnessRewriter.class);

	private TBoxReasoner reasoner;
	private ImmutableOntologyVocabulary voc;
	private CQContainmentCheckUnderLIDs dataDependenciesCQC;
	private LinearInclusionDependencies sigma;
	
	private Collection<TreeWitnessGenerator> generators;
	
	@Override
	public void setTBox(TBoxReasoner reasoner, ImmutableOntologyVocabulary voc, LinearInclusionDependencies sigma) {
		double startime = System.currentTimeMillis();

		this.reasoner = reasoner;
		this.voc = voc;
		this.sigma = sigma;
		
		dataDependenciesCQC = new CQContainmentCheckUnderLIDs(sigma);
		
		generators = TreeWitnessGenerator.getTreeWitnessGenerators(reasoner);
		
//		log.debug("SET SIGMA");
//		for (Axiom ax : sigma.getAssertions()) {
//			log.debug("SIGMA: " + ax);
//		}
		
		double endtime = System.currentTimeMillis();
		double tm = (endtime - startime) / 1000;
		time += tm;
		log.debug(String.format("setTBox time: %.3f s (total %.3f s)", tm, time));		
	}
	
	
	
	/*
	 * returns an atom with given arguments and the predicate name formed by the given URI basis and string fragment
	 */
	
	private static Function getHeadAtom(String base, String suffix, List<Term> arguments) {
		Predicate predicate = TERM_FACTORY.getPredicate(base + suffix, arguments.size());
		return TERM_FACTORY.getFunction(predicate, arguments);
	}
	
	private int freshVarIndex = 0;
	
	private Variable getFreshVariable() {
		freshVarIndex++;
		return TERM_FACTORY.getVariable("twr" + freshVarIndex);
	}
	
	/*
	 * returns atoms E of a given collection of tree witness generators; 
	 * the `free' variable of the generators is replaced by the term r0;
	 */

	private List<Function> getAtomsForGenerators(Collection<TreeWitnessGenerator> gens, Term r0)  {
		Collection<ClassExpression> concepts = TreeWitnessGenerator.getMaximalBasicConcepts(gens, reasoner);		
		List<Function> genAtoms = new ArrayList<>(concepts.size());
		
		for (ClassExpression con : concepts) {
			log.debug("  BASIC CONCEPT: {}", con);
			Function atom; 
			if (con instanceof OClass) {
				atom = TERM_FACTORY.getFunction(((OClass)con).getPredicate(), r0);
			}
			else if (con instanceof ObjectSomeValuesFrom) {
				ObjectPropertyExpression some = ((ObjectSomeValuesFrom)con).getProperty();
				atom = (!some.isInverse()) ?  
						TERM_FACTORY.getFunction(some.getPredicate(), r0, getFreshVariable()) :
							TERM_FACTORY.getFunction(some.getPredicate(), getFreshVariable(), r0);
			}
			else {
				DataPropertyExpression some = ((DataSomeValuesFrom)con).getProperty();
				atom = TERM_FACTORY.getFunction(some.getPredicate(), r0, getFreshVariable());
			}
			genAtoms.add(atom);
		}
		return genAtoms;
	}
	
	
	
	/*
	 * rewrites a given connected CQ with the rules put into output
	 */
	
	private List<CQIE> rewriteCC(QueryConnectedComponent cc, Function headAtom,  DatalogProgram edgeDP) {
		
		List<CQIE> outputRules = new LinkedList<>();	
		String headURI = headAtom.getFunctionSymbol().getName();
		
		TreeWitnessSet tws = TreeWitnessSet.getTreeWitnesses(cc, reasoner, voc, generators);

		if (cc.hasNoFreeTerms()) {  
			if (!cc.isDegenerate() || cc.getLoop() != null) 
				for (Function a : getAtomsForGenerators(tws.getGeneratorsOfDetachedCC(), getFreshVariable())) {
					outputRules.add(DATALOG_FACTORY.getCQIE(headAtom, a));
				}
		}

		// COMPUTE AND STORE TREE WITNESS FORMULAS
		for (TreeWitness tw : tws.getTWs()) {
			log.debug("TREE WITNESS: {}", tw);		
			List<Function> twf = new LinkedList<Function>(); 
			
			// equality atoms
			Iterator<Term> i = tw.getRoots().iterator();
			Term r0 = i.next();
			while (i.hasNext()) 
				twf.add(TERM_FACTORY.getFunctionEQ(i.next(), r0));
			
			// root atoms
			for (Function a : tw.getRootAtoms()) {
				Predicate predicate = a.getFunctionSymbol();
				twf.add((predicate.getArity() == 1) ? TERM_FACTORY.getFunction(predicate, r0) : TERM_FACTORY.getFunction(predicate, r0, r0));
			}
			
			List<Function> genAtoms = getAtomsForGenerators(tw.getGenerators(), r0);			
			boolean subsumes = false;
//			for (Function a : genAtoms) 				
//				if (twf.subsumes(a)) {
//					subsumes = true;
//					log.debug("TWF {} SUBSUMES {}", twf.getAllAtoms(), a);
//					break;
//				}

			List<List<Function>> twfs = new ArrayList<List<Function>>(subsumes ? 1 : genAtoms.size());			
//			if (!subsumes) {
				for (Function a : genAtoms) {				
					LinkedList<Function> twfa = new LinkedList<Function>(twf);
					twfa.add(a); // 
					twfs.add(twfa);
				}
//			}
//			else
//				twfs.add(twf.getAllAtoms());
			
			tw.setFormula(twfs);
		}
				
		if (!cc.isDegenerate()) {			
			if (tws.hasConflicts()) { 
				// there are conflicting tree witnesses
				// use compact exponential rewriting by enumerating all compatible subsets of tree witnesses
				CompatibleTreeWitnessSetIterator iterator = tws.getIterator();
				while (iterator.hasNext()) {
					Collection<TreeWitness> compatibleTWs = iterator.next();
					log.debug("COMPATIBLE: {}", compatibleTWs);
					LinkedList<Function> mainbody = new LinkedList<Function>(); 
					
					for (Edge edge : cc.getEdges()) {
						boolean contained = false;
						for (TreeWitness tw : compatibleTWs)
							if (tw.getDomain().contains(edge.getTerm0()) && tw.getDomain().contains(edge.getTerm1())) {
								contained = true;
								log.debug("EDGE {} COVERED BY {}", edge, tw);
								break;
							}
						if (!contained) {
							log.debug("EDGE {} NOT COVERED BY ANY TW",  edge);
							mainbody.addAll(edge.getAtoms());
						}
					}
					for (TreeWitness tw : compatibleTWs) {
						Function twAtom = getHeadAtom(headURI, "_TW_" + (edgeDP.getRules().size() + 1), cc.getVariables());
						mainbody.add(twAtom);				
						for (List<Function> twfa : tw.getFormula())
							edgeDP.appendRule(DATALOG_FACTORY.getCQIE(twAtom, twfa));
					}	
					mainbody.addAll(cc.getNonDLAtoms());					
					outputRules.add(DATALOG_FACTORY.getCQIE(headAtom, mainbody));
				}
			}
			else {
				// no conflicting tree witnesses
				// use polynomial tree witness rewriting by treating each edge independently 
				LinkedList<Function> mainbody = new LinkedList<Function>(); 		
				for (Edge edge : cc.getEdges()) {
					log.debug("EDGE {}", edge);
					
					Function edgeAtom = null;
					for (TreeWitness tw : tws.getTWs())
						if (tw.getDomain().contains(edge.getTerm0()) && tw.getDomain().contains(edge.getTerm1())) {
							if (edgeAtom == null) {
								//IRI atomURI = edge.getBAtoms().iterator().next().getPredicate().getName();
								edgeAtom = getHeadAtom(headURI, 
										"_EDGE_" + (edgeDP.getRules().size() + 1) /*+ "_" + atomURI.getRawFragment()*/, cc.getVariables());
								mainbody.add(edgeAtom);				
								
								LinkedList<Function> edgeAtoms = new LinkedList<Function>(); 
								edgeAtoms.addAll(edge.getAtoms());
								edgeDP.appendRule(DATALOG_FACTORY.getCQIE(edgeAtom, edgeAtoms));
							}
							
							for (List<Function> twfa : tw.getFormula())
								edgeDP.appendRule(DATALOG_FACTORY.getCQIE(edgeAtom, twfa));
						}
					
					if (edgeAtom == null) // no tree witnesses -- direct insertion into the main body
						mainbody.addAll(edge.getAtoms());
				}
				mainbody.addAll(cc.getNonDLAtoms());
				outputRules.add(DATALOG_FACTORY.getCQIE(headAtom, mainbody));
			}
		}
		else {
			// degenerate connected component
			LinkedList<Function> loopbody = new LinkedList<Function>();
			Loop loop = cc.getLoop();
			log.debug("LOOP {}", loop);
			if (loop != null)
				loopbody.addAll(loop.getAtoms());
			loopbody.addAll(cc.getNonDLAtoms());
			outputRules.add(DATALOG_FACTORY.getCQIE(headAtom, loopbody));
		}
		return outputRules;
	}
	
	private double time = 0;
	
	@Override
	public DatalogProgram rewrite(DatalogProgram dp) {
		
		double startime = System.currentTimeMillis();
		
		List<CQIE> outputRules = new LinkedList<>();
		DatalogProgram ccDP = null;
		DatalogProgram edgeDP = DATALOG_FACTORY.getDatalogProgram();

		for (CQIE cqie : dp.getRules()) {
			List<QueryConnectedComponent> ccs = QueryConnectedComponent.getConnectedComponents(cqie);	
			Function cqieAtom = cqie.getHead();
		
			if (ccs.size() == 1) {
				QueryConnectedComponent cc = ccs.iterator().next();
				log.debug("CONNECTED COMPONENT ({})" + " EXISTS {}", cc.getFreeVariables(), cc.getQuantifiedVariables());
				log.debug("     WITH EDGES {} AND LOOP {}", cc.getEdges(), cc.getLoop());
				log.debug("     NON-DL ATOMS {}", cc.getNonDLAtoms());
				outputRules.addAll(rewriteCC(cc, cqieAtom, edgeDP)); 				
			}
			else {
				if (ccDP == null)
					ccDP = DATALOG_FACTORY.getDatalogProgram();
				String cqieURI = cqieAtom.getFunctionSymbol().getName();
				List<Function> ccBody = new ArrayList<Function>(ccs.size());
				for (QueryConnectedComponent cc : ccs) {
					log.debug("CONNECTED COMPONENT ({})" + " EXISTS {}", cc.getFreeVariables(), cc.getQuantifiedVariables());
					log.debug("     WITH EDGES {} AND LOOP {}", cc.getEdges(), cc.getLoop());
					log.debug("     NON-DL ATOMS {}", cc.getNonDLAtoms());
					Function ccAtom = getHeadAtom(cqieURI, "_CC_" + (ccDP.getRules().size() + 1), cc.getFreeVariables());
					List<CQIE> list = rewriteCC(cc, ccAtom, edgeDP); 
					ccDP.appendRule(list);
					ccBody.add(ccAtom);
				}
				outputRules.add(DATALOG_FACTORY.getCQIE(cqieAtom, ccBody));
			}
		}
		
		log.debug("REWRITTEN PROGRAM\n{}CC DEFS\n{}", outputRules, ccDP);
		if (!edgeDP.getRules().isEmpty()) {
			log.debug("EDGE DEFS\n{}", edgeDP);			
			outputRules = DatalogQueryServices.plugInDefinitions(outputRules, edgeDP);
			if (ccDP != null)
				ccDP = DATALOG_FACTORY.getDatalogProgram(dp.getQueryModifiers(),
						DatalogQueryServices.plugInDefinitions(ccDP.getRules(), edgeDP));
			log.debug("INLINE EDGE PROGRAM\n{}CC DEFS\n{}", outputRules, ccDP);
		}
		if (ccDP != null) {
			outputRules = DatalogQueryServices.plugInDefinitions(outputRules, ccDP);
			log.debug("INLINE CONNECTED COMPONENTS PROGRAM\n{}", outputRules);
		}
	
		// extra CQC 
		if (outputRules.size() > 1) 
			CQCUtilities.removeContainedQueries(outputRules, dataDependenciesCQC);
		
		DatalogProgram output = DATALOG_FACTORY.getDatalogProgram(dp.getQueryModifiers(), outputRules);
		for (CQIE cq : output.getRules())
			CQCUtilities.optimizeQueryWithSigmaRules(cq.getBody(), sigma);

		double endtime = System.currentTimeMillis();
		double tm = (endtime - startime) / 1000;
		time += tm;
		log.debug(String.format("Rewriting time: %.3f s (total %.3f s)", tm, time));
		log.debug("Final rewriting:\n{}", output);
		return output;
	}
}
