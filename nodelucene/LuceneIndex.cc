#include <iostream>

#include <boost/exception/diagnostic_information.hpp>
#include <boost/filesystem.hpp>

#define LPP_HAVE_DLL

#include "LuceneIndex.h"
#include "FileUtils.h"
#include "FuzzyTermEnum.h"
#include "Highlighter.h"
#include "QueryScorer.h"
#include "SimpleFragmenter.h"
#include "SimpleHTMLFormatter.h"

using namespace Lucene;

namespace nodelucene {

    using v8::Function;
    using v8::FunctionCallbackInfo;
    using v8::FunctionTemplate;
    using v8::Isolate;
    using v8::Local;
    using v8::Number;
    using v8::Object;
    using v8::Persistent;
    using v8::String;
    using v8::Value;


    void InitAll(Local<Object> exports) {
        LuceneIndex::Init(exports);
    }

    NODE_MODULE(addon, InitAll)

    Persistent<Function> LuceneIndex::constructor;

    LuceneIndex::LuceneIndex(std::string name) {
        indexName = name;
        Lucene::String dirName(StringUtils::toUnicode(indexName));
        if (!boost::filesystem::is_directory(dirName)) {
            // create empty index if missing
            indexWriter = newLucene<IndexWriter>(
                    FSDirectory::open(dirName),
                    newLucene<StandardAnalyzer>(LuceneVersion::LUCENE_CURRENT),
                    true,
                    IndexWriter::MaxFieldLengthLIMITED
            );
            indexWriter->optimize();
            indexWriter->close();
        }

        indexReader = IndexReader::open(FSDirectory::open(dirName), true);
        searcher = newLucene<IndexSearcher>(indexReader);
        analyzer = newLucene<StandardAnalyzer>(LuceneVersion::LUCENE_CURRENT);
        parser = newLucene<QueryParser>(LuceneVersion::LUCENE_CURRENT, L"contents", analyzer);
    }



    LuceneIndex::~LuceneIndex() {
    }

    void LuceneIndex::Init(Local<Object> exports) {
        Isolate* isolate = exports->GetIsolate();

        // Prepare constructor template
        Local<FunctionTemplate> tpl = FunctionTemplate::New(isolate, New);
        tpl->SetClassName(String::NewFromUtf8(isolate, "LuceneIndex"));
        tpl->InstanceTemplate()->SetInternalFieldCount(1);

        // Prototype
        NODE_SET_PROTOTYPE_METHOD(tpl, "startWriting", StartWriting);
        NODE_SET_PROTOTYPE_METHOD(tpl, "addFile", AddFile);
        NODE_SET_PROTOTYPE_METHOD(tpl, "endWriting", EndWriting);
        NODE_SET_PROTOTYPE_METHOD(tpl, "search", Search);
        NODE_SET_PROTOTYPE_METHOD(tpl, "highlight", Highlight);
        NODE_SET_PROTOTYPE_METHOD(tpl, "suggestTerm", SuggestTerm);

        constructor.Reset(isolate, tpl->GetFunction());
        exports->Set(String::NewFromUtf8(isolate, "LuceneIndex"),
                     tpl->GetFunction());
    }

    void LuceneIndex::New(const FunctionCallbackInfo<Value>& args) {
        Isolate* isolate = args.GetIsolate();

        if (args.IsConstructCall()) {
            // Invoked as constructor: `new LuceneIndex(...)`
            std::string name = args[0]->IsUndefined() ? "SearchIndex" : *String::Utf8Value(args[0]);
            LuceneIndex * obj = new LuceneIndex(name);
            obj->Wrap(args.This());
            args.GetReturnValue().Set(args.This());
        } else {
            // Invoked as plain function `LuceneIndex(...)`, turn into construct call.
            const int argc = 1;
            Local<Value> argv[argc] = { args[0] };
            Local<Function> cons = Local<Function>::New(isolate, constructor);
            args.GetReturnValue().Set(cons->NewInstance(argc, argv));
        }
    }

    void LuceneIndex::AddFile(const FunctionCallbackInfo<Value> &args) {
        Isolate* isolate = args.GetIsolate();

        LuceneIndex * obj = ObjectWrap::Unwrap<LuceneIndex>(args.Holder());

        if (!args[0]->IsString()) {
            args.GetReturnValue().Set(
                    String::NewFromUtf8(isolate, "first arg must be string")
            );
            return;
        }
        if (!args[1]->IsString()) {
            args.GetReturnValue().Set(
                    String::NewFromUtf8(isolate, "second arg must be string")
            );
            return;
        }

        Local<String> fileName = args[0]->ToString();
        Local<String> contents = args[1]->ToString();

        DocumentPtr doc = newLucene<Document>();

        doc->add(newLucene<Field>(
                L"path",
                StringUtils::toUnicode(*String::Utf8Value(fileName)),
                Field::STORE_YES,
                Field::INDEX_NOT_ANALYZED
        ));
        doc->add(newLucene<Field>(
                L"contents",
                StringUtils::toUnicode(*String::Utf8Value(contents)),
                Field::STORE_NO,
                Field::INDEX_ANALYZED
        ));

        obj->indexWriter->addDocument(doc);

        args.GetReturnValue().Set(
                String::NewFromUtf8(isolate, "success!")
        );
    }

    void LuceneIndex::StartWriting(const v8::FunctionCallbackInfo<v8::Value> &args) {
        LuceneIndex * obj = ObjectWrap::Unwrap<LuceneIndex>(args.Holder());

        Lucene::String dirName(StringUtils::toUnicode(obj->indexName));
        obj->indexWriter = newLucene<IndexWriter>(
                FSDirectory::open(dirName),
                newLucene<StandardAnalyzer>(LuceneVersion::LUCENE_CURRENT),
                true,
                IndexWriter::MaxFieldLengthLIMITED
        );
    }

    void LuceneIndex::EndWriting(const v8::FunctionCallbackInfo<v8::Value> &args) {
        LuceneIndex * obj = ObjectWrap::Unwrap<LuceneIndex>(args.Holder());
        obj->indexWriter->optimize();
        obj->indexWriter->close();
    }

    void LuceneIndex::Search(const v8::FunctionCallbackInfo<v8::Value> &args) {
        Isolate* isolate = args.GetIsolate();
        LuceneIndex * obj = ObjectWrap::Unwrap<LuceneIndex>(args.Holder());

        if (!args[0]->IsString()) {
            args.GetReturnValue().Set(
                    String::NewFromUtf8(isolate, "first arg must be string")
            );
            return;
        }

        Local<String> queryStr = args[0]->ToString();
        Lucene::String queryLStr(StringUtils::toUnicode(*String::Utf8Value(queryStr)));

        TopScoreDocCollectorPtr collector = TopScoreDocCollector::create(10, false);

        Local<v8::Array> ret = v8::Array::New(isolate);
        args.GetReturnValue().Set(ret);

        try {
            QueryPtr query = obj->parser->parse(queryLStr);
            obj->searcher->search(query, collector);

            Collection<ScoreDocPtr> hits = collector->topDocs()->scoreDocs;

            for (int32_t i = 0; i < hits.size(); ++i) {
                DocumentPtr doc = obj->searcher->doc(hits[i]->doc);
                ret->Set(i, String::NewFromUtf8(isolate, (StringUtils::toUTF8(doc->get(L"path"))).c_str()));
            }
        } catch (std::exception const& e) {
            std::cerr << e.what() << std::endl;
        } catch (boost::exception const& e) {
            std::cerr << boost::diagnostic_information(e) << std::endl;
        }
    }

    void LuceneIndex::Highlight(const v8::FunctionCallbackInfo<v8::Value> &args) {
        Isolate* isolate = args.GetIsolate();

        LuceneIndex * obj = ObjectWrap::Unwrap<LuceneIndex>(args.Holder());

        if (!args[0]->IsString()) {
            args.GetReturnValue().Set(
                    String::NewFromUtf8(isolate, "first arg must be string")
            );
            return;
        }
        if (!args[1]->IsString()) {
            args.GetReturnValue().Set(
                    String::NewFromUtf8(isolate, "second arg must be string")
            );
            return;
        }

        Local<String> contentsStr = args[0]->ToString();
        Local<String> queryStr = args[1]->ToString();
        Lucene::String contentsLStr(StringUtils::toUnicode(*String::Utf8Value(contentsStr)));
        Lucene::String queryLStr(StringUtils::toUnicode(*String::Utf8Value(queryStr)));

        QueryPtr query = obj->parser->parse(queryLStr);

        FormatterPtr fmt = newLucene<SimpleHTMLFormatter>();
        HighlighterScorerPtr scr = newLucene<QueryScorer>(query);
        Highlighter hl(fmt, scr);
        hl.setTextFragmenter(newLucene<SimpleFragmenter>(500));

        args.GetReturnValue().Set(
                String::NewFromUtf8(
                        isolate,
                        (StringUtils::toUTF8(
                                hl.getBestFragment(obj->analyzer, L"contents", contentsLStr)
                        )).c_str()
                )
        );
    }

    void LuceneIndex::SuggestTerm(const v8::FunctionCallbackInfo<v8::Value> &args) {
        Isolate* isolate = args.GetIsolate();

        LuceneIndex * obj = ObjectWrap::Unwrap<LuceneIndex>(args.Holder());

        if (!args[0]->IsString()) {
            args.GetReturnValue().Set(
                    String::NewFromUtf8(isolate, "first arg must be string")
            );
            return;
        }

        Local<String> termNodeStr = args[0]->ToString();
        std::string termStr(*String::Utf8Value(termNodeStr));

        FuzzyTermEnum en(
                obj->indexReader,
                newLucene<Term>(L"contents", StringUtils::toUnicode(termStr.c_str())),
                0.5
        );

        int best = 0;
        std::string bestStr;
        while (en.next()) {
            if (en.docFreq() > best) {
                best = en.docFreq();
                bestStr = StringUtils::toUTF8(en.term()->text());
            }
        }

        args.GetReturnValue().Set(
                String::NewFromUtf8(
                        isolate,
                        bestStr.c_str()
                )
        );
    }

}  // namespace nodelucene