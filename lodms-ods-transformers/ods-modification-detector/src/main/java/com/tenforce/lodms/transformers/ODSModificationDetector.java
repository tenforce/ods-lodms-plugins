package com.tenforce.lodms.transformers;

import at.punkt.lodms.base.TransformerBase;
import at.punkt.lodms.integration.ConfigurationException;
import at.punkt.lodms.spi.transform.TransformContext;
import at.punkt.lodms.spi.transform.TransformException;
import com.vaadin.Application;
import com.vaadin.terminal.ClassResource;
import com.vaadin.terminal.Resource;
import org.openrdf.model.Literal;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.StatementImpl;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.openrdf.query.BindingSet;
import org.openrdf.query.MalformedQueryException;
import org.openrdf.query.QueryEvaluationException;
import org.openrdf.query.QueryLanguage;
import org.openrdf.query.TupleQuery;
import org.openrdf.query.TupleQueryResult;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ODSModificationDetector extends TransformerBase<ODSModificationDetectorConfig> {
    private final ValueFactory valueFactory = ValueFactoryImpl.getInstance();
    private final URI DCT_MODIFIED = valueFactory.createURI("http://purl.org/dc/terms/modified");
    private final URI ADMS_STATUS = valueFactory.createURI("http://www.w3.org/ns/adms#status");


    @Override
    protected void configureInternal(ODSModificationDetectorConfig config) throws ConfigurationException {
    }

    @Override
    public void transform(Repository repository, URI graph, TransformContext context) throws TransformException {
        try {
            URI currentGraph = (URI) context.getCustomData().get("virtuosoExtractorGraph");
            URI previousGraph = valueFactory.createURI(currentGraph.stringValue() + "previous");
            List<Statement> statements = generateDateStaments(repository, currentGraph, previousGraph, graph);
            RepositoryConnection con = repository.getConnection();
            try {
                con.add(statements, graph);
                con.commit();
            } finally {
                con.close();
            }
        } catch (Exception e) {
            throw new TransformException(e.getMessage(), e);
        }

    }

    private List<Statement> generateDateStaments(Repository repository, URI currentGraph, URI previousGraph, URI workingGraph) throws TransformException {
        Map<String, DataSetInfo> oldHashes = getSetsWithHashAndDate(repository, previousGraph);
        Map<String, DataSetInfo> currentHashes = getSetsWithHashAndDate(repository, currentGraph);
        List<Statement> statements = new ArrayList<Statement>();
        Map<String, String> harmonizedLinks = getHarmonizedLinks(repository, workingGraph);
        for (Map.Entry<String, DataSetInfo> oldInfo : oldHashes.entrySet()) {
            if (currentHashes.containsKey(oldInfo.getKey())) {
                // if the dataset still exists, check for changes
                DataSetInfo newInfo = currentHashes.get(oldInfo.getKey());
                String harmonizedSubject = harmonizedLinks.get(oldInfo.getKey());
                if (newInfo.getHashCode() != oldInfo.getValue().getHashCode()) {
                    // changed
                    statements.add(valueFactory.createStatement(valueFactory.createURI(harmonizedSubject), ADMS_STATUS, valueFactory.createLiteral("http://www.w3.org/ns/adms#updated")));
                    statements.add(generateModifiedStatement(harmonizedSubject, newInfo.getDate()));
                } else {
                    statements.add(generateModifiedStatement(harmonizedSubject, oldInfo.getValue().getDate()));
                    statements.add(valueFactory.createStatement(valueFactory.createURI(harmonizedSubject), ADMS_STATUS, valueFactory.createLiteral("http://www.w3.org/ns/adms#updated")));

                }
                currentHashes.remove(oldInfo.getKey());
            } else {
                // it's deleted created deleted datarecord


            }
        }

        for (Map.Entry<String, DataSetInfo> newInfo : currentHashes.entrySet()) {
            // all dataset still in currentHashes are new
            statements.add(generateModifiedStatement(harmonizedLinks.get(newInfo.getKey()), newInfo.getValue().getDate()));
            statements.add(valueFactory.createStatement(valueFactory.createURI(harmonizedLinks.get(newInfo.getKey())), ADMS_STATUS, valueFactory.createLiteral("http://www.w3.org/ns/adms#created")));
        }
        return statements;
    }

    private Statement generateModifiedStatement(String subject, Literal date) {
        if (null == subject)
            throw new IllegalArgumentException("subject can't be null");
        else
            return new StatementImpl(valueFactory.createURI(subject), DCT_MODIFIED, date);
    }

    @Override
    public String getName() {
        return "ODS Modification Detector";
    }

    public Map<String, String> getHarmonizedLinks(Repository repository, URI graph) throws TransformException {
        Map<String, String> map = new HashMap<String, String>();
        try {
            RepositoryConnection con = repository.getConnection();
            RepositoryResult<Statement> r = con.getStatements(null, valueFactory.createURI("http://data.opendatasupport.eu/ontology/harmonisation.owl#raw_dataset"), null, false, graph);
            for (Statement s : r.asList()) {
                map.put(s.getObject().toString(), s.getSubject().toString());
            }
            r.close();
            con.close();
        } catch (RepositoryException e) {
            throw new TransformException(e.getMessage(), e);
        }
        return map;
    }

    public Map<String, DataSetInfo> getSetsWithHashAndDate(Repository repository, URI graph) throws TransformException {
        String sparql = "prefix dcat:<http://www.w3.org/ns/dcat#> select ?record,?hash,?date FROM <" + graph + "> where {" +
                "{?record a dcat:Dataset }" +
                "{?record <http://data.opendatasupport.eu/ontology/harmonisation.owl#content_hash> ?hash}" +
                "{?record <http://data.opendatasupport.eu/ontology/harmonisation.owl#harvest_date> ?date}" +
                "}";
        Map<String, DataSetInfo> resultMap = new HashMap<String, DataSetInfo>();
        try {
            RepositoryConnection con = repository.getConnection();
            TupleQuery query = con.prepareTupleQuery(QueryLanguage.SPARQL, sparql);
            TupleQueryResult result = query.evaluate();
            while (result.hasNext()) {
                BindingSet set = result.next();
                Value record = (Value) set.getBinding("record").getValue();
                Literal date = (Literal) set.getBinding("date").getValue();
                Literal hashValue = (Literal) set.getBinding("hash").getValue();
                int hash = hashValue.intValue();
                resultMap.put(record.toString(), new DataSetInfo(hash, date));
                result.close();
            }
            con.close();

        } catch (RepositoryException e) {
            throw new TransformException(e.getMessage(), e);
        } catch (MalformedQueryException ignored) {

        } catch (QueryEvaluationException e) {
            throw new TransformException(e.getMessage(), e);
        }
        return resultMap;
    }

    @Override
    public String getDescription() {
        return "Creates a modification date for the catalog record by comparing the current raw data with the previous harvest.";
    }

    @Override
    public Resource getIcon(Application application) {
        return new ClassResource("/com/tenforce/lodms/transform/ods.png", application);
    }

    @Override
    public String asString() {
        return getName();
    }

    private class DataSetInfo {
        // start stepping through the array from the beginning
        private int hashCode;
        private Literal date;

        private DataSetInfo(int hashCode, Literal date) {
            this.hashCode = hashCode;
            this.date = date;
        }

        public int getHashCode() {
            return hashCode;
        }

        public Literal getDate() {
            return date;
        }
    }
}
