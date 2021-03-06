package it.unibz.inf.ontop.docker.postgres;

import it.unibz.inf.ontop.docker.AbstractVirtualModeTest;
import org.junit.Ignore;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LeftJoinProfTestPgSQL extends AbstractVirtualModeTest {

    private static final String owlFileName = "/redundant_join/redundant_join_fk_test.owl";
    private static final String obdaFileName = "/redundant_join/redundant_join_fk_test.obda";
    private static final String propertyFileName = "/pgsql/redundant_join_fk_test.properties";
    private static final String NO_SELF_LJ_OPTIMIZATION_MSG = "The table professors should be used only once";

    public LeftJoinProfTestPgSQL() {
        super(owlFileName, obdaFileName, propertyFileName);
    }


    @Test
    public void testSimpleFirstName() throws Exception {

        String query = "PREFIX : <http://www.semanticweb.org/user/ontologies/2016/8/untitled-ontology-84#>\n" +
                "\n" +
                "SELECT ?v\n" +
                "WHERE {\n" +
                "   ?p a :Professor .\n" +
                "   OPTIONAL {\n" +
                "     ?p :firstName ?v\n" +
                "  }\n" +
                "}";


        String[] expectedValues = {"Roger", "Frank", "John", "Michael", "Diego", "Johann", "Barbara", "Mary"};
        String sql = checkReturnedValuesAndReturnSql(query, Arrays.asList(expectedValues));

        System.out.println("SQL Query: \n" + sql);

        assertFalse(NO_SELF_LJ_OPTIMIZATION_MSG, containsMoreThanOneOccurrence(sql, "\"professors\""));
        assertFalse(NO_SELF_LJ_OPTIMIZATION_MSG, containsMoreThanOneOccurrence(sql, "\"PROFESSORS\""));
    }

    @Test
    public void testFullName1() throws Exception {

        String query = "PREFIX : <http://www.semanticweb.org/user/ontologies/2016/8/untitled-ontology-84#>\n" +
                "\n" +
                "SELECT ?v\n" +
                "WHERE {\n" +
                "   ?p a :Professor .\n" +
                "   OPTIONAL {\n" +
                "     ?p :firstName ?v ;\n" +
                "          :lastName ?lastName .\n" +
                "  }\n" +
                "}";

        String[] expectedValues = {"Roger", "Frank", "John", "Michael", "Diego", "Johann", "Barbara", "Mary"};
        String sql = checkReturnedValuesAndReturnSql(query, Arrays.asList(expectedValues));

        System.out.println("SQL Query: \n" + sql);

        assertFalse(NO_SELF_LJ_OPTIMIZATION_MSG, containsMoreThanOneOccurrence(sql, "\"professors\""));
        assertFalse(NO_SELF_LJ_OPTIMIZATION_MSG, containsMoreThanOneOccurrence(sql, "\"PROFESSORS\""));
    }

    @Test
    public void testFullName2() throws Exception {

        String query = "PREFIX : <http://www.semanticweb.org/user/ontologies/2016/8/untitled-ontology-84#>\n" +
                "\n" +
                "SELECT ?v\n" +
                "WHERE {\n" +
                "   ?p a :Professor .\n" +
                "   OPTIONAL {\n" +
                "     ?p :firstName ?v .\n" +
                "   }\n" +
                "   OPTIONAL {\n" +
                "     ?p :lastName ?lastName .\n" +
                "   }\n" +
                "}";

        String[] expectedValues = {"Roger", "Frank", "John", "Michael", "Diego", "Johann", "Barbara", "Mary"};
        String sql = checkReturnedValuesAndReturnSql(query, Arrays.asList(expectedValues));

        System.out.println("SQL Query: \n" + sql);

        assertFalse(NO_SELF_LJ_OPTIMIZATION_MSG, containsMoreThanOneOccurrence(sql, "\"professors\""));
        assertFalse(NO_SELF_LJ_OPTIMIZATION_MSG, containsMoreThanOneOccurrence(sql, "\"PROFESSORS\""));
    }

    @Test
    public void testFirstNameNickname() throws Exception {

        String query = "PREFIX : <http://www.semanticweb.org/user/ontologies/2016/8/untitled-ontology-84#>\n" +
                "\n" +
                "SELECT ?v\n" +
                "WHERE {\n" +
                "   ?p a :Professor .\n" +
                "   OPTIONAL {\n" +
                "     ?p :firstName ?v ;\n" +
                "          :nickname ?nickname .\n" +
                "  }\n" +
                "}";

        String[] expectedValues = {
                "Roger", "Frank", "John", "Michael"};
        String sql = checkReturnedValuesAndReturnSql(query, Arrays.asList(expectedValues));

        System.out.println("SQL Query: \n" + sql);

        assertFalse(sql.toUpperCase().contains("LEFT"));
    }

    @Test
    public void testSimpleNickname() throws Exception {

        String query = "PREFIX : <http://www.semanticweb.org/user/ontologies/2016/8/untitled-ontology-84#>\n" +
                "\n" +
                "SELECT ?v\n" +
                "WHERE {\n" +
                "   ?p a :Professor .\n" +
                "   OPTIONAL {\n" +
                "     ?p :nickname ?v\n" +
                "  }\n" +
                "}";

        String[] expectedValues = {
                "Rog", "Frankie", "Johnny", "King of Pop"
        };
        String sql = checkReturnedValuesAndReturnSql(query, Arrays.asList(expectedValues));


        System.out.println("SQL Query: \n" + sql);

        assertFalse(NO_SELF_LJ_OPTIMIZATION_MSG, containsMoreThanOneOccurrence(sql.toLowerCase(), "\"professors\""));
    }

    @Test
    public void testNicknameAndCourse() throws Exception {

        String query = "PREFIX : <http://www.semanticweb.org/user/ontologies/2016/8/untitled-ontology-84#>\n" +
                "\n" +
                "SELECT ?v ?f\n" +
                "WHERE {\n" +
                "   ?p a :Professor ;\n" +
                "      :firstName ?f ;\n" +
                "      :teaches ?c .\n" +
                "   OPTIONAL {\n" +
                "     ?p :nickname ?v\n" +
                "  }\n" +
                "}";

        String[] expectedValues = {
                "Rog", "Rog", "Johnny"
        };
        String sql = checkReturnedValuesAndReturnSql(query, Arrays.asList(expectedValues));

        System.out.println("SQL Query: \n" + sql);

        assertFalse(NO_SELF_LJ_OPTIMIZATION_MSG, containsMoreThanOneOccurrence(sql.toLowerCase(), "\"professors\""));
    }

    @Test
    public void testCourseTeacherName() throws Exception {

        String query = "PREFIX : <http://www.semanticweb.org/user/ontologies/2016/8/untitled-ontology-84#>\n" +
                "\n" +
                "SELECT DISTINCT ?v\n" +
                "WHERE {\n" +
                "   ?p :teaches ?c .\n" +
                "   OPTIONAL {\n" +
                "     ?p :lastName ?v\n" +
                "  }\n" +
                "}";

        String[] expectedValues = {
                "Smith", "Poppins", "Depp"
        };
        String sql = checkReturnedValuesAndReturnSql(query, Arrays.asList(expectedValues));


        System.out.println("SQL Query: \n" + sql);

        assertFalse(sql.toUpperCase().contains("LEFT"));
    }

    @Test
    public void testCourseJoinOnLeft1() throws Exception {

        String query = "PREFIX : <http://www.semanticweb.org/user/ontologies/2016/8/untitled-ontology-84#>\n" +
                "\n" +
                "SELECT DISTINCT ?v\n" +
                "WHERE {\n" +
                "   ?p :firstName ?f ; \n" +
                "      :teaches ?c .\n" +
                "   OPTIONAL {\n" +
                "     ?p :lastName ?v\n" +
                "  }\n" +
                "FILTER (bound(?f))" +
                "}";

        String[] expectedValues = {
                "Smith", "Poppins", "Depp"
        };
        String sql = checkReturnedValuesAndReturnSql(query, Arrays.asList(expectedValues));


        System.out.println("SQL Query: \n" + sql);

        assertFalse(sql.toUpperCase().contains("LEFT"));
    }

    @Test
    public void testCourseJoinOnLeft2() throws Exception {

        String query = "PREFIX : <http://www.semanticweb.org/user/ontologies/2016/8/untitled-ontology-84#>\n" +
                "\n" +
                "SELECT DISTINCT ?v\n" +
                "WHERE {\n" +
                "   ?p :firstName ?v ; \n" +
                "      :teaches ?c .\n" +
                "   OPTIONAL {\n" +
                "     ?p :lastName ?v\n" +
                "  }\n" +
                "}";

        String[] expectedValues = {
                "John", "Mary", "Roger"
        };
        String sql = checkReturnedValuesAndReturnSql(query, Arrays.asList(expectedValues));

        System.out.println("SQL Query: \n" + sql);

        assertFalse(sql.toUpperCase().contains("LEFT"));
    }

    @Ignore("Support preferences")
    @Test
    public void testPreferences() throws Exception {

        String query = "PREFIX : <http://www.semanticweb.org/user/ontologies/2016/8/untitled-ontology-84#>\n" +
                "\n" +
                "SELECT DISTINCT ?v\n" +
                "WHERE {\n" +
                "   ?p a :Professor . \n" +
                "   OPTIONAL { \n" +
                "     ?p :nickname ?v .\n" +
                "   }\n" +
                "   OPTIONAL {\n" +
                "     ?p :lastName ?v\n" +
                "  }\n" +
                "}\n" +
                "ORDER BY ?v";

        String[] expectedValues = {
                "Dodero", "Frankie", "Gamper", "Helmer", "Johnny", "King of Pop", "Poppins", "Rog"
        };
        String sql = checkReturnedValuesAndReturnSql(query, Arrays.asList(expectedValues));

        System.out.println("SQL Query: \n" + sql);

        assertTrue(sql.toUpperCase().contains("LEFT"));
    }

    @Test
    public void testUselessRightPart2() throws Exception {

        String query = "PREFIX : <http://www.semanticweb.org/user/ontologies/2016/8/untitled-ontology-84#>\n" +
                "\n" +
                "SELECT DISTINCT ?v\n" +
                "WHERE {\n" +
                "   ?p a :Professor . \n" +
                "   OPTIONAL { \n" +
                "     ?p :lastName ?v .\n" +
                "   }\n" +
                "   OPTIONAL {\n" +
                "     ?p :firstName ?v\n" +
                "  }\n" +
                "}\n" +
                "ORDER BY ?v";

        String[] expectedValues = {
                "Depp", "Dodero", "Gamper", "Helmer", "Jackson", "Pitt", "Poppins", "Smith"};

        String sql = checkReturnedValuesAndReturnSql(query, Arrays.asList(expectedValues));
        System.out.println("SQL Query: \n" + sql);

        assertFalse(sql.toUpperCase().contains("LEFT"));
    }

    private static boolean containsMoreThanOneOccurrence(String query, String pattern) {
        int firstOccurrenceIndex = query.indexOf(pattern);
        if (firstOccurrenceIndex >= 0) {
            return query.substring(firstOccurrenceIndex + 1).contains(pattern);
        }
        return false;
    }

}
