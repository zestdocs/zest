#include <cstring>
#include <iostream>
#include <archive.h>
#include <archive_entry.h>

#ifdef _WIN32
#include <io.h>
#define write _write
#endif

void print_size(char* filename) {
    archive *a = archive_read_new();
    archive_read_support_filter_bzip2(a);
    archive_read_support_format_7zip(a);

    archive_read_open_filename(a, filename, 10240);

    archive_entry *entry;
    archive_read_next_header(a, &entry);

    std::cout << archive_entry_size(entry);

    archive_read_free(a);
}

void extract(char* filename) {
    archive *a = archive_read_new();
    archive_read_support_filter_bzip2(a);
    archive_read_support_format_7zip(a);

    archive_read_open_filename(a, filename, 10240);

    archive_entry *entry;
    archive_read_next_header(a, &entry);
    archive_read_data_into_fd(a, 1);

    archive_read_free(a);
}

int main(int argc, char** argv) {
    if (argc == 5 && strcmp(argv[4], "sizes") == 0) {
        print_size(argv[1]);
        std::cout << " ";
        print_size(argv[2]);
        std::cout << " ";
        print_size(argv[3]);
        std::cout << std::endl;
    } else {
        extract(argv[1]); // Path to Posts.xml 7z file
        write(1, "\0", 1); // null separator, as expected in main.cpp
        extract(argv[2]); // Path to Comments.xml 7z file
        write(1, "\0", 1); // null separator, as expected in main.cpp
        extract(argv[3]); // Path to Users.xml 7z file
    }
}
