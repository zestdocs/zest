#include <sqlite3ext.h>
#include <string.h>
SQLITE_EXTENSION_INIT1


// ported from DevDocs' searcher.coffee

void match_fuzzy(
        const char* needle, const char* haystack,
        int* start, int* len, int* needle_len
) {
    int i = 0, j = 0;
    for (; needle[i] != 0; ++i) {
        while(haystack[j] != 0) {
            if (needle[i] == haystack[j++]) {
                if (*start == -1) *start = j - 1;  // first matched char
                *len = j - *start;
                goto outer;
            }
        }
        *start = -1;  // end of haystack, char not found
        return;
        outer: continue;
    }
    if (needle_len)
        *needle_len = i;
}

int max(int a, int b) {
    if (a > b) return a;
    else return b;
}

int score_exact(int match_index, int match_len, const char* value) {
    int score = 100, value_len = strlen(value);
    // Remove one point for each unmatched character.
    score -= (value_len - match_len);
    if (match_index > 0) {
        if (value[match_index - 1] == '.') {
            // If the character preceding the query is a dot, assign the same
            // score as if the query was found at the beginning of the string,
            // minus one.
            score += (match_index - 1);
        } else if (match_len == 1) {
            // Don't match a single-character query unless it's found at the
            // beginning of the string or is preceded by a dot.
            return 0;
        } else {
            // (1) Remove one point for each unmatched character up to
            //     the nearest preceding dot or the beginning of the
            //     string.
            // (2) Remove one point for each unmatched character
            //     following the query.
            int i = match_index - 2;
            while (i >= 0 && value[i] != '.') --i;
            score -= (match_index - i) +                     // (1)
                     (value_len - match_len - match_index);  // (2)
        }
        // Remove one point for each dot preceding the query, except for the
        // one immediately before the query.
        int separators = 0,
            i = match_index - 2;
        while (i >= 0) {
            if (value[i] == '.') {
                separators += 1;
            }
            i--;
        }
        score -= separators;
    }

    // Remove five points for each dot following the query.
    int separators = 0;
    int i = value_len - match_len - match_index - 1;
    while (i >= 0){
        if (value[match_index + match_len + i] == '.') {
            separators += 1;
        }
        i--;
    }
    score -= separators * 5;

    return max(1, score);
}

int score_fuzzy(int match_index, int match_len, const char* value) {
    if (match_index == 0 || value[match_index-1] == '.') {
        return max(66, 100 - match_len);
    } else {
        if (value[match_index + match_len - 1] == 0) {
            return max(33, 67 - match_len);
        } else {
            return max(1, 34 - match_len);
        }
    }
}

static void scoreFunc(
    sqlite3_context *context,
    int argc,
    sqlite3_value **argv
) {
    const char* needle = sqlite3_value_text(argv[0]);
    const char* haystack = sqlite3_value_text(argv[1]);
    int match1 = -1, match1_len, needle_len;
    match_fuzzy(needle, haystack, &match1, &match1_len, &needle_len);
    if (match1 == -1) {
        sqlite3_result_int(context, 0);
        return;
    }

    if (needle_len == match1_len) { // exact match
        sqlite3_result_int(context, score_exact(
            match1, match1_len, haystack
        ));
        return;
    }

    int best = score_fuzzy(match1, match1_len, haystack);
    int last_index_of_dot = -1, i;
    for (i = 0; haystack[i] != 0; ++i) {
        if (haystack[i] == '.') last_index_of_dot = i;
    }
    if (last_index_of_dot != -1) {
        int match2 = -1, match2_len;
        match_fuzzy(
            needle, haystack + last_index_of_dot + 1, &match2, &match2_len,
            0
        );
        if (match2 != -1) {
            best = max(best,
                score_fuzzy(match2, match2_len, haystack + last_index_of_dot + 1)
            );
        }
    }
    sqlite3_result_int(context, best);
}


#ifdef _WIN32
__declspec(dllexport)
#endif
int sqlite3_extension_init(
    sqlite3 *db,
    char **pzErrMsg,
    const sqlite3_api_routines *pApi
) {
    SQLITE_EXTENSION_INIT2(pApi)
    sqlite3_create_function(db, "zestScore", 2, SQLITE_ANY, 0, scoreFunc, 0, 0);
    return 0;
}