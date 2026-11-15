#!/usr/bin/env bash
# Lists the pull requests merged since the documentation was last reviewed,
# so the docs can be brought up to date from a known point.
#
# The review point is the commit recorded in DOCS_REVIEWED (next to this
# module's pom.xml). Everything merged up to that commit is reflected in the
# docs; everything after it still needs a look.
#
#   docs-pending.sh                 list PRs merged since the review point
#   docs-pending.sh --files         also list the files each PR changed
#   docs-pending.sh --mark [<rev>]  record <rev> (default: HEAD) as reviewed
#
# Reads git history only. It needs no network access and no GitHub token.
set -euo pipefail

module_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo_root="$(git -C "$module_dir" rev-parse --show-toplevel)"
module_name="$(basename "$module_dir")"
marker="$module_dir/DOCS_REVIEWED"

usage() { sed -n '2,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

read_marker() {
    [ -f "$marker" ] || { echo "error: $marker is missing" >&2; exit 2; }
    local sha
    sha="$(awk -F': *' '$1 == "commit" { print $2 }' "$marker")"
    [ -n "$sha" ] || { echo "error: no 'commit:' line in $marker" >&2; exit 2; }
    git -C "$repo_root" cat-file -e "$sha^{commit}" 2>/dev/null || {
        echo "error: reviewed commit $sha is not in this repository; fetch the branch it was recorded on" >&2
        exit 2
    }
    echo "$sha"
}

mark() {
    local rev="${1:-HEAD}" sha
    sha="$(git -C "$repo_root" rev-parse --verify "$rev^{commit}")"
    {
        echo "# The documentation in this module reflects the code up to this commit."
        echo "# Update it with scripts/docs-pending.sh --mark after reviewing the pending PRs."
        echo "commit: $sha"
        echo "committed: $(git -C "$repo_root" log -1 --format=%cs "$sha")"
        echo "reviewed: $(date +%Y-%m-%d)"
    } > "$marker"
    echo "Recorded $sha as the documentation review point in ${marker#"$repo_root"/}"
}

list() {
    local show_files="$1" base head count=0 docs_only=0
    base="$(read_marker)"
    head="$(git -C "$repo_root" rev-parse HEAD)"
    if ! git -C "$repo_root" merge-base --is-ancestor "$base" "$head"; then
        echo "error: review point $base is not an ancestor of HEAD; check out the documented branch" >&2
        exit 2
    fi
    echo "Documentation reviewed through $(git -C "$repo_root" log -1 --format='%h (%cs)' "$base")"
    echo "Comparing with HEAD $(git -C "$repo_root" log -1 --format='%h (%cs)' "$head")"
    echo

    # One entry per change on the mainline: a merge commit for a merged PR,
    # or a single commit for a squash merge or a direct push.
    local sha subject title pr files code_files modules touched_docs
    while IFS= read -r sha; do
        subject="$(git -C "$repo_root" log -1 --format=%s "$sha")"
        title="$subject"
        pr=""
        case "$subject" in
            "Merge pull request #"*)
                pr="${subject#Merge pull request #}"; pr="#${pr%% *}"
                title="$(git -C "$repo_root" log -1 --format=%b "$sha" | sed -n '/./{p;q;}')"
                [ -n "$title" ] || title="$subject"
                ;;
            *"(#"[0-9]*")")
                pr="#$(echo "$subject" | sed -E 's/.*\(#([0-9]+)\)$/\1/')"
                ;;
        esac
        files="$(git -C "$repo_root" diff --name-only "$sha^1" "$sha")"
        code_files="$(echo "$files" | grep -v -E "^($module_name/|docs?/)|\.(md|adoc)$" || true)"
        if [ -z "$code_files" ]; then
            docs_only=$((docs_only + 1))
            continue
        fi
        count=$((count + 1))
        modules="$(echo "$code_files" | awk -F/ '{ print (NF > 1 ? $1 : "(root)") }' | sort -u | paste -sd, - | sed 's/,/, /g')"
        touched_docs="no"
        echo "$files" | grep -q "^$module_name/src/" && touched_docs="yes"
        printf '%s  %-6s %s\n' "$(git -C "$repo_root" log -1 --format=%cs "$sha")" "${pr:-direct}" "$title"
        printf '    modules: %s\n' "$modules"
        printf '    updated %s in the same change: %s\n' "$module_name" "$touched_docs"
        if [ "$show_files" = "yes" ]; then
            echo "$code_files" | sed 's/^/      /'
        fi
    done < <(git -C "$repo_root" rev-list --first-parent --reverse "$base..$head")

    echo
    echo "$count change(s) to review; $docs_only documentation-only change(s) skipped."
    if [ "$count" -gt 0 ]; then
        echo "After updating the docs, run: ${BASH_SOURCE[0]#"$repo_root"/} --mark"
    fi
}

case "${1:-}" in
    "")       list no ;;
    --files)  list yes ;;
    --mark)   mark "${2:-HEAD}" ;;
    -h|--help) usage ;;
    *) echo "error: unknown argument: $1" >&2; usage >&2; exit 2 ;;
esac
