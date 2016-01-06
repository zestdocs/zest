#include <exception>
#include <iostream>
#include <map>
#include <set>
#include <vector>

#include <rapidjson/writer.h>
#include <rapidjson/stringbuffer.h>

#include <xercesc/framework/StdInInputSource.hpp>
#include <xercesc/parsers/SAXParser.hpp>
#include <xercesc/sax/HandlerBase.hpp>
#include <xercesc/util/BinFileInputStream.hpp>

#include <leveldb/db.h>

using namespace rapidjson;
using namespace std;
using namespace xercesc;


class ZeroSeparatedBinFileInputStream : public BinFileInputStream {

public:
    virtual XMLSize_t readBytes(XMLByte *const toFill, const XMLSize_t maxToRead) override;

    ZeroSeparatedBinFileInputStream (
            const   FileHandle      toUse
            , MemoryManager* const  manager = XMLPlatformUtils::fgMemoryManager
    ) : BinFileInputStream(toUse, manager) { }

    static void next() {
        doNext = true;
    }

private:
    static XMLByte * buf;
    static XMLSize_t bufLen;
    static bool doNext;
    static XMLSize_t done, prevStatus;
    static time_t t0;

    // -----------------------------------------------------------------------
    //  Unimplemented constructors and operators
    // -----------------------------------------------------------------------
    ZeroSeparatedBinFileInputStream(const ZeroSeparatedBinFileInputStream&) : BinFileInputStream("") { };
    ZeroSeparatedBinFileInputStream& operator=(const ZeroSeparatedBinFileInputStream&);

};

bool ZeroSeparatedBinFileInputStream::doNext = false;
XMLSize_t ZeroSeparatedBinFileInputStream::bufLen = 0;
XMLSize_t ZeroSeparatedBinFileInputStream::done = 0;
XMLSize_t ZeroSeparatedBinFileInputStream::prevStatus = 0;
XMLByte * ZeroSeparatedBinFileInputStream::buf = nullptr;
time_t ZeroSeparatedBinFileInputStream::t0 = time(nullptr);

XMLSize_t ZeroSeparatedBinFileInputStream::readBytes(XMLByte *const toFill, const XMLSize_t maxToRead) {
    XMLSize_t ret;
    if (bufLen && doNext) {
        if (maxToRead <= bufLen) {
            copy(buf, buf + maxToRead, toFill);
            buf += maxToRead;
            bufLen -= maxToRead;
            return maxToRead;
        } else {
            copy(buf, buf + bufLen, toFill);
            ret = bufLen + BinFileInputStream::readBytes(toFill + bufLen, maxToRead - bufLen);
            bufLen = 0;
            doNext = false;
        }
    } else if (bufLen && !doNext) {
        return 0;
    } else {
        XMLSize_t all = BinFileInputStream::readBytes(toFill, maxToRead);
        XMLByte * cur = toFill;
        while ((cur - toFill < all) && *cur != '\0') ++cur;
        if (cur - toFill < all) {
            ret = (cur - toFill);
            bufLen = toFill + all - cur - 1;
            buf = new XMLByte[bufLen];
            copy(cur + 1, toFill + all, buf);
        } else {
            ret = all;
        }
    }
    done += ret;
    if (done - prevStatus >= 10000*1000) {
        time_t dt = time(nullptr) - t0;
        const long long all = 9601970627 + 30613095889;
        if (dt > 0)
            std::cout << done << " " << (all/done) << " " << (all*dt/done - dt) << endl;
        prevStatus = done;
    }
    return ret;
}


class ZeroPaddedStdInInputSource : public InputSource {
    virtual BinInputStream *makeStream() const override;

public:
    ZeroPaddedStdInInputSource(MemoryManager* const manager = XMLPlatformUtils::fgMemoryManager)
            : InputSource("stdin", manager) { }
    ~ZeroPaddedStdInInputSource() { }
};

BinInputStream *ZeroPaddedStdInInputSource::makeStream() const {
    BinFileInputStream* retStream = new (getMemoryManager()) ZeroSeparatedBinFileInputStream(
            XMLPlatformUtils::openStdInHandle(getMemoryManager())
    );

    if (!retStream->getIsOpen()) {
        delete retStream;
        return 0;
    }

    return retStream;
}


class Answer;

class Item {
public:
    map<string, string> base;
    vector<map<string, string> > comments;

    virtual const vector<Answer*>& getAnswers()=0;
    virtual bool isQuestion()=0;
    virtual void addAnswer(Answer* a)=0;
};

class Answer : public Item {
    bool isQuestion() { return false; }
    const vector<Answer*>& getAnswers() { throw exception(); }
    void addAnswer(Answer* a) { throw exception(); }

};

class Question : public Item {
    vector<Answer*> answers;

    bool isQuestion() { return true; }
    const vector<Answer*>& getAnswers() { return answers; }
    void addAnswer(Answer* a) { answers.push_back(a); }
};

class MySAXHandler : public HandlerBase {


    bool comments = false;

    void a2m(AttributeList& attrs, map<string, string>& ret) {
        for (int i = 0; i < attrs.getLength(); ++i) {
            const XMLCh* name = attrs.getName(i);
            ret[XMLString::transcode(name)] = XMLString::transcode(attrs.getValue(name));
        }
    }

public:

    map<string, Item*> items;
    void setComments() { comments = true; }

    Question* a2q(AttributeList &attrs) {
        Question *ret = new Question();
        for (int i = 0; i < attrs.getLength(); ++i) {
            const XMLCh* name = attrs.getName(i);
            ret->base[XMLString::transcode(name)] = XMLString::transcode(attrs.getValue(name));
        }
        return ret;
    }
    Answer* a2a(AttributeList &attrs) {
        Answer *ret = new Answer();
        for (int i = 0; i < attrs.getLength(); ++i) {
            const XMLCh* name = attrs.getName(i);
            ret->base[XMLString::transcode(name)] = XMLString::transcode(attrs.getValue(name));
        }
        return ret;
    }

    void startElement(const XMLCh* const, AttributeList& attrs) {
        if (attrs.getValue("Id") != nullptr) {
            if (comments) {
                map<string, Item*>::iterator found = items.find(XMLString::transcode(attrs.getValue("PostId")));
                if (found != items.end()) {
                    found->second->comments.push_back(map<string,string>());
                    a2m(attrs, found->second->comments.back());
                }
            } else {
                bool ispy = false;
                const XMLCh* tags = attrs.getValue("Tags");

                if (tags == nullptr) {
                    const XMLCh* parentId = attrs.getValue("ParentId");
                    if (parentId == nullptr) {
                        return;
                    }

                    ispy = items.find(XMLString::transcode(parentId)) != items.end();
                } else {
                    const string tagsStr = XMLString::transcode(tags);
                    ispy = tagsStr.find("<python>") != string::npos;
                }

                if (ispy) {
                    const string postType = XMLString::transcode(attrs.getValue("PostTypeId"));
                    const string id = XMLString::transcode(attrs.getValue("Id"));
                    if (postType == "1") {
                        items[id] = a2q(attrs);
                    } else {
                        Answer* a = a2a(attrs);
                        items[id] = a;
                        items[XMLString::transcode(attrs.getValue("ParentId"))]->addAnswer(a);
                    }
                }
            }
        }
    }
};



int main() {
    try {
        XMLPlatformUtils::Initialize();
    } catch (const XMLException& e) {
        return 1;
    }

    SAXParser *parser = new SAXParser();
    MySAXHandler *handler = new MySAXHandler();
    parser->setDocumentHandler(handler);
    ZeroPaddedStdInInputSource src;
    parser->parse(src);

    ZeroSeparatedBinFileInputStream::next();
    handler->setComments();
    parser->parse(src);

    leveldb::DB* db;
    leveldb::Options options;
    options.create_if_missing = true;
    leveldb::DB::Open(options, "python", &db);

    for (auto it = handler->items.begin(); it != handler->items.end(); ++it) {
        Item *i = it->second;

        if (!i->isQuestion()) {
            continue;
        }

        StringBuffer s;
        Writer<StringBuffer> w(s);
        w.StartObject();

        // core question data:
        for (auto it2 = i->base.begin(); it2 != i->base.end(); ++it2) {
            w.String(it2->first.c_str());
            w.String(it2->second.c_str());
        }

        // answers:
        w.String("answers");
        w.StartArray();
        auto answers = i->getAnswers();
        for (int j = 0; j < answers.size(); ++j) {
            auto answer = answers[j];
            w.StartObject();
            for (auto it2 = answer->base.begin(); it2 != answer->base.end(); ++it2) {
                w.String(it2->first.c_str());
                w.String(it2->second.c_str());
            }

            // answer comments
            w.String("comments");
            w.StartArray();
            for (auto it2 = answer->comments.begin(); it2 != answer->comments.end(); ++it2) {
                w.StartObject();
                for (auto it3 = it2->begin(); it3 != it2->end(); ++it3) {
                    w.String(it3->first.c_str());
                    w.String(it3->second.c_str());
                }
                w.EndObject();
            }
            w.EndArray();
            w.EndObject();
        }
        w.EndArray();

        // comments
        w.String("comments");
        w.StartArray();
        for (auto it2 = i->comments.begin(); it2 != i->comments.end(); ++it2) {
            w.StartObject();
            for (auto it3 = it2->begin(); it3 != it2->end(); ++it3) {
                w.String(it3->first.c_str());
                w.String(it3->second.c_str());
            }
            w.EndObject();
        }
        w.EndArray();

        w.EndObject();

        db->Put(leveldb::WriteOptions(), it->first, s.GetString());
    }

    delete db;

    return 0;
}