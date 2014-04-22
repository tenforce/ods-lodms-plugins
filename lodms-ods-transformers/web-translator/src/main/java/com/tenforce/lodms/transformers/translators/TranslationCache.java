package com.tenforce.lodms.transformers.translators;

import info.aduna.iteration.Iterations;
import org.openrdf.model.Literal;
import org.openrdf.model.Model;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.impl.LinkedHashModel;
import org.openrdf.model.impl.LiteralImpl;
import org.openrdf.model.impl.StatementImpl;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.query.MalformedQueryException;
import org.openrdf.query.QueryEvaluationException;
import org.openrdf.query.QueryLanguage;
import org.openrdf.query.TupleQuery;
import org.openrdf.query.TupleQueryResult;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;

import java.util.Collection;
import java.util.UUID;

public class TranslationCache {
  /* custom RDF class for translations */
  private static final URI KLASS_TRANS = new URIImpl("ods:Translation");
  /* predicate for original value */
  private static final URI PRED_ORIG = new URIImpl("ods:orig");
  /* predicate for translation   */
  private static final URI PRED_TRANS = new URIImpl("ods:translation");
  Repository repository;
  URI translatorCache;

  /**
   * @param translatorCache
   */
  public TranslationCache(Repository repository, URI translatorCache) {
    this.repository = repository;
    this.translatorCache = translatorCache;
  }

  /**
   * checks whether the object of the provided statement has a translation in the translationCache
   *
   * @param statement to be translated
   */
  public boolean hasTranslation(Statement statement) throws RepositoryException {
    Literal literalWithoutLang = new LiteralImpl(statement.getObject().stringValue());
    return getConnection().hasStatement(null, PRED_ORIG, literalWithoutLang, false, translatorCache);
  }

  /**
   * creates a translatedStatement using the translationCache
   *
   * @param s statement to be translated
   * @return
   */
  public TranslatedStatement translate(Statement s) {
    try {
      TranslatedStatement translatedStatement = new TranslatedStatement();
      translatedStatement.setOriginalStatement(s);
      Statement newStatement = null;
      newStatement = new StatementImpl(s.getSubject(), s.getPredicate(), getTranslationFor(s.getObject()));
      translatedStatement.setTranslatedStatement(newStatement);
      return translatedStatement;
    } catch (RepositoryException e) {
      throw new IllegalStateException(e);
    } catch (MalformedQueryException e) {
      throw new IllegalStateException(e);
    } catch (QueryEvaluationException e) {
      throw new IllegalStateException(e);
    }
  }

  private Value getTranslationFor(Value object) throws RepositoryException, MalformedQueryException, QueryEvaluationException {
    String query = "" +
            "SELECT ?translation " +
            "FROM <" + translatorCache + "> " +
            "WHERE {" +
            " ?subject <" + PRED_ORIG + "> \"" + object.stringValue() + "\". " +
            " ?subject <" + PRED_TRANS + "> ?translation" +
            "}";

    TupleQuery tupleQuery = getConnection().prepareTupleQuery(QueryLanguage.SPARQL, query);
    TupleQueryResult result = tupleQuery.evaluate();
    if (result.hasNext())
      return result.next().getValue("translation");
    else
      throw new IllegalArgumentException("no translation found");
  }

  /**
   * Add translations to the translation cache
   * Only adds new translations
   *
   * @param statementList
   * @throws RepositoryException
   */
  public void addTranslations(Collection<TranslatedStatement> statementList) throws RepositoryException {
    Model translations = new LinkedHashModel();
    for (TranslatedStatement s : statementList) {
      if (!hasTranslation(s.getOriginalStatement())) {
        String orig = s.getOriginalStatement().getObject().stringValue();
        String trans = s.getTranslatedStatement().getObject().stringValue();
        translations.addAll(modelTranslation(orig, trans));
      }
    }
    addTranslationToCache(translations);
  }

  private Model modelTranslation(String orig, String trans) {
    Model translation = new LinkedHashModel();
    URI translationURI = new URIImpl("ods:translation#" + UUID.randomUUID().toString());
    translation.add(translationURI, RDF.TYPE, KLASS_TRANS);
    translation.add(translationURI, PRED_ORIG, new LiteralImpl(orig));
    translation.add(translationURI, PRED_TRANS, new LiteralImpl(trans, "en"));
    return translation;
  }

  private void addTranslationToCache(Model translation) throws RepositoryException {
    getConnection().add(translation, translatorCache);
  }

  private Model retrieveStatements() throws RepositoryException {
    RepositoryConnection connection = getConnection();
    RepositoryResult<Statement> statements = connection.getStatements(null, null, null, false, translatorCache);
    return new LinkedHashModel(Iterations.asList(statements));
  }

  private RepositoryConnection getConnection() {
    try {
      return repository.getConnection();
    } catch (RepositoryException e) {
      throw new IllegalStateException(e);
    }
  }
}