package it.unibz.inf.ontop.owlrefplatform.owlapi;

import it.unibz.inf.ontop.owlrefplatform.core.ExecutableQuery;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLException;

import java.util.List;

/***
 * A Statement to execute queries over a OntopOWLConnection. The logic of this
 * statement is equivalent to that of JDBC's Statements.
 *
 * <p>
 * <strong>Performance</strong> Note that you should not create multiple
 * statements over the same connection to execute parallel queries (see
 * {@link QuestOWLConnection}). Multiple statements over the same connection are
 * not going to be very useful until we support updates (then statements will
 * allow to implement transactions in the same way as JDBC Statements).
 *
 * Initial @author Mariano Rodriguez Muro <mariano.muro@gmail.com>
 *
 * Used by the OWLAPI.
 *
 *
 */
public interface OntopOWLStatement extends AutoCloseable {
	void cancel() throws OWLException;

	void close() throws OWLException;

	QuestOWLResultSet executeTuple(String query) throws OWLException;

	List<OWLAxiom> executeGraph(String query) throws OWLException;

	OntopOWLConnection getConnection() throws OWLException;

	boolean isClosed() throws OWLException;

	void setQueryTimeout(int seconds) throws OWLException;

	long getTupleCount(String query) throws OWLException;

	String getRewriting(String query) throws OWLException;

	ExecutableQuery getExecutableQuery(String query) throws OWLException;

}