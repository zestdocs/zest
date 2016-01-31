#include <string>

#include <boost/exception/diagnostic_information.hpp>
#include <boost/filesystem.hpp>

#include "LuceneHeaders.h"
#include "FileUtils.h"
#include "FuzzyTermEnum.h"
#include "Highlighter.h"
#include "QueryScorer.h"
#include "SimpleFragmenter.h"
#include "SimpleHTMLFormatter.h"

using namespace std;
using namespace Lucene;

int main(int argc, char **argv) {
    string indexName = argv[1];

    Lucene::IndexReaderPtr indexReader;
    Lucene::SearcherPtr searcher;
    Lucene::AnalyzerPtr analyzer;
    Lucene::QueryParserPtr parser;

    Lucene::String dirName(StringUtils::toUnicode(indexName));

    indexReader = IndexReader::open(FSDirectory::open(dirName), true);
    searcher = newLucene<IndexSearcher>(indexReader);
    analyzer = newLucene<StandardAnalyzer>(LuceneVersion::LUCENE_CURRENT);
    parser = newLucene<QueryParser>(LuceneVersion::LUCENE_CURRENT, L"contents", analyzer);

    string queryStr;

    while (std::getline(cin, queryStr)) {
        Lucene::String queryLStr(StringUtils::toUnicode(queryStr));

        TopScoreDocCollectorPtr collector = TopScoreDocCollector::create(10, false);


        try {
            QueryPtr query = parser->parse(queryLStr);
            searcher->search(query, collector);

            Collection<ScoreDocPtr> hits = collector->topDocs()->scoreDocs;

            for (int32_t i = 0; i < hits.size(); ++i) {
                DocumentPtr doc = searcher->doc(hits[i]->doc);
                cout << StringUtils::toUTF8(doc->get(L"path")) << endl;
            }
        } catch (std::exception const &e) {
            std::cerr << e.what() << std::endl;
        } catch (boost::exception const &e) {
            std::cerr << boost::diagnostic_information(e) << std::endl;
        }
        cout << "END" << std::endl;
    }
}
