#ifndef NODELUCENE_LUCENEINDEX_H
#define NODELUCENE_LUCENEINDEX_H

#include <node.h>
#include <node_object_wrap.h>

#include "LuceneHeaders.h"

namespace nodelucene {

    class LuceneIndex : public node::ObjectWrap {
    public:
        static void Init(v8::Local<v8::Object> exports);

    private:
        explicit LuceneIndex(std::string name);
        ~LuceneIndex();

        static void New(const v8::FunctionCallbackInfo<v8::Value>& args);
        static void AddFile(const v8::FunctionCallbackInfo<v8::Value> &args);
        static void StartWriting(const v8::FunctionCallbackInfo<v8::Value> &args);
        static void EndWriting(const v8::FunctionCallbackInfo<v8::Value> &args);
        static void Search(const v8::FunctionCallbackInfo<v8::Value> &args);
        static void Highlight(const v8::FunctionCallbackInfo<v8::Value> &args);
        static void SuggestTerm(const v8::FunctionCallbackInfo<v8::Value> &args);
        static v8::Persistent<v8::Function> constructor;

        std::string indexName;
        Lucene::IndexWriterPtr indexWriter;

        Lucene::IndexReaderPtr indexReader;
        Lucene::SearcherPtr searcher;
        Lucene::AnalyzerPtr analyzer;
        Lucene::QueryParserPtr parser;
    };

}  // namespace demo

#endif //NODELUCENE_LUCENEINDEX_H
