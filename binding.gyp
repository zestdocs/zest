{
  "targets": [
    {
      "target_name": "nodelucene",
      "sources": [ "nodelucene/LuceneIndex.cc" ],
      "libraries": [
          "-llucene++",
          "-llucene++-contrib",
          "-L/usr/local/lib",
          # for Circle CI:
          "-L/home/ubuntu/installprefix/lib/x86_64-linux-gnu",
          "-Wl,-rpath,\\$$ORIGIN/resources"
      ],
      "xcode_settings": {
        "OTHER_CFLAGS": [
          "-std=c++11", "-stdlib=libc++", "-mmacosx-version-min=10.7", "-fexceptions"
        ],
      },
      "cflags!": [ "-fno-exceptions", "-fno-rtti" ],
      "cflags_cc!": [ "-fno-exceptions", "-fno-rtti" ],
      "include_dirs": [
        "/usr/local/include/lucene++",
        "/usr/local/include",
        # for Circle CI:
        "/home/ubuntu/installprefix/include/lucene++"
      ],
    }
  ]
}
