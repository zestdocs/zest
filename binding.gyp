{
  "targets": [
    {
      "target_name": "nodelucene",
      "sources": [ "nodelucene/LuceneIndex.cc" ],
      "libraries": [ "-llucene++", "-llucene++-contrib", "-L/usr/local/lib" ],
      "xcode_settings": {
        "OTHER_CFLAGS": [
          "-std=c++11", "-stdlib=libc++", "-mmacosx-version-min=10.7", "-fexceptions"
        ],
      },
      "include_dirs": [
        "/usr/local/include/lucene++",
        "/usr/local/include"
      ],
    }
  ]
}