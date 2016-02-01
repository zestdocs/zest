#include <exception>
#include <iostream>
#include <map>
#include <set>
#include <string>
#include <vector>
#include <ctime>

#ifndef WIN32
#include <unistd.h>
#endif

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

vector<string> tagNames;
leveldb::DB* db;

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
    static bool doNext;
    static XMLSize_t bufLen;
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
    if (buf != nullptr && doNext) {
        if (maxToRead <= bufLen) {
            copy(buf, buf + maxToRead, toFill);
            buf += maxToRead;
            done += maxToRead;
            bufLen -= maxToRead;
            return maxToRead;
        } else {
            copy(buf, buf + bufLen, toFill);
#ifdef _WIN32
            ret = bufLen + BinFileInputStream::readBytes(toFill + bufLen, maxToRead - bufLen);
#else
            ret = bufLen + read(0, toFill + bufLen, maxToRead - bufLen);
#endif
            bufLen = 0;
            delete[] buf;
            buf = nullptr;
            doNext = false;
        }
    } else if (buf != nullptr && !doNext) {
        return 0;
    } else {
#ifdef _WIN32
        XMLSize_t all = BinFileInputStream::readBytes(toFill, maxToRead);
#else
        XMLSize_t all = read(0, toFill, maxToRead);
#endif
        XMLByte * cur = toFill;
        while ((cur - toFill < all) && *cur != 0) ++cur;
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
        std::cout << done << std::endl;
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

inline const string ch2str(const XMLCh* c) {
    char* tmp = XMLString::transcode(c);
    string ret = string(tmp);
    XMLString::release(&tmp);
    return ret;
}

class MySAXHandler : public HandlerBase {


    bool comments = false;
    bool users = false;

    map<string, int> commentCounts;
    map<string, int> answerCounts;

public:
    void setComments() { comments = true; }
    void setUsers() { users = true; }

    const std::string jsonize(AttributeList &attrs) {
        StringBuffer s;
        Writer<StringBuffer> w(s);

        w.StartObject();
        for (int i = 0; i < attrs.getLength(); ++i) {
            const XMLCh* name = attrs.getName(i);
            char *tmpName = XMLString::transcode(name),
                 *tmpValue = XMLString::transcode(attrs.getValue(name));
            w.String(tmpName);
            w.String(tmpValue);
            XMLString::release(&tmpName);
            XMLString::release(&tmpValue);
        }
        w.EndObject();

        return s.GetString();
    }

    void startElement(const XMLCh* const, AttributeList& attrs) {
        if (attrs.getValue("Id") != nullptr) {
            if (users) {
                const string UserId = ch2str(attrs.getValue("Id"));
                db->Put(
                    leveldb::WriteOptions(),
                    "u_"+UserId,
                    jsonize(attrs)
                );
            } else if (comments) {
                const string PostId = ch2str(attrs.getValue("PostId"));
                map<string, int>::iterator match = commentCounts.find(PostId);
                if (match != commentCounts.end()) {
                    db->Put(
                        leveldb::WriteOptions(),
                        "c_"+PostId+"_"+std::to_string(match->second),
                        jsonize(attrs)
                    );
                    commentCounts[PostId] += 1;
                }
            } else {
                const XMLCh* tags = attrs.getValue("Tags");

                if (tags == nullptr) {
                    const XMLCh* parentId = attrs.getValue("ParentId");
                    if (parentId == nullptr) {
                        return;
                    }
                    const string id = ch2str(attrs.getValue("Id"));
                    const string transcodedParentId = ch2str(parentId);
                    auto match = answerCounts.find(transcodedParentId);
                    if (match != answerCounts.end()) {
                        db->Put(
                            leveldb::WriteOptions(),
                            "a_"+transcodedParentId+"_"+std::to_string(match->second),
                            jsonize(attrs)
                        );
                        answerCounts[transcodedParentId] += 1;
                        commentCounts[id] = 0;
                    }
                } else {
                    bool matches = false;

                    const string tagsStr = ch2str(tags);

                    for (int i = 0; i < tagNames.size(); ++i) {
                        matches = tagsStr.find("<"+tagNames[i]+">") != string::npos;
                        if (matches) break;
                    }

                    if (matches) {
                        const string id = ch2str(attrs.getValue("Id"));
                        const string postType = ch2str(attrs.getValue("PostTypeId"));

                        if (postType == "1") {
                            db->Put(
                                leveldb::WriteOptions(),
                                "p_"+id,
                                jsonize(attrs)
                            );
                            answerCounts[id] = 0;
                            commentCounts[id] = 0;
                        } else {
                            const XMLCh* parentId = attrs.getValue("ParentId");
                            if (parentId == nullptr) {
                                return;
                            }
                            const string transcodedParentId = ch2str(parentId);
                            auto match = answerCounts.find(transcodedParentId);
                            if (match != answerCounts.end()) {
                                db->Put(
                                    leveldb::WriteOptions(),
                                    "a_"+transcodedParentId+"_"+std::to_string(match->second),
                                    jsonize(attrs)
                                );
                                answerCounts[transcodedParentId] += 1;
                                commentCounts[id] = 0;
                            }
                        }

                    }
                }
            }
        }
    }
};


int main(int argc, const char ** argv) {
    for (int i = 1; i < argc; ++i) {
        tagNames.push_back(argv[i]);
    }
    try {
        XMLPlatformUtils::Initialize();
    } catch (const XMLException& e) {
        return 1;
    }

    leveldb::Options options;
    options.create_if_missing = true;
    leveldb::DB::Open(options, "leveldb", &db);

    SAXParser *parser = new SAXParser();
    MySAXHandler *handler = new MySAXHandler();
    parser->setDocumentHandler(handler);
    ZeroPaddedStdInInputSource src;
    parser->parse(src);

    ZeroSeparatedBinFileInputStream::next();
    handler->setComments();
    parser->parse(src);

    ZeroSeparatedBinFileInputStream::next();
    handler->setUsers();
    parser->parse(src);

    delete db;

    return 0;
}
