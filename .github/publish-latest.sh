#!/bin/bash
#
# publish-latest.sh BRANCH VERSION FILE... — update the one release "latest"
# with the files of a v2 or basic build (the same script lives in both
# branches).  The release is updated in place, never deleted: v2 and basic
# each replace only their own files.
#
#   - v2 moves the "latest" tag to the commit it built and sets the title
#     "Decoder <version>"; basic keeps the tag and the title;
#   - the notes hold one line per branch, "Decoder <v2 version> (...)" and
#     "Legacy `basic` <version> (...)"; each build rewrites its own line;
#   - a SHA256SUMS left from earlier releases is removed (no checksums, #236).
#
# Writes new=true to $GITHUB_OUTPUT when the branch's version differs from
# the one the release had, so only a new version goes to Telegram.
# Needs GH_TOKEN, GITHUB_REPOSITORY, GITHUB_SHA, GITHUB_SERVER_URL.
#
# shellcheck disable=SC2016  # literal backticks: Markdown in the release notes
set -euo pipefail

branch=$1 ver=$2
shift 2
short=${GITHUB_SHA::7}
url="${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}"

# Only the branch head is published: a run of a commit that has been
# superseded meanwhile (a manual run, a waiting push) must not roll the
# release back. The newer commit's own run publishes it.
head=$(git ls-remote origin "refs/heads/$branch" | cut -f 1)
if [ "$head" != "$GITHUB_SHA" ]; then
    echo "::notice::$branch is at ${head:0:7} now, not ${short}: not published"
    exit 0
fi

mapping=0
for f in "$@"; do
    [ "$(basename "$f")" = decoder-v2-mapping.txt ] && mapping=1
done

# The release as it is now. "Not found" means there is none yet; any other
# failure stops here, before anything is changed.
if title=$(gh release view latest --json name -q .name 2>err.txt); then
    exists=1
    body=$(gh release view latest --json body -q .body)
elif grep -qi 'not found' err.txt; then
    exists=0 title="" body=""
else
    cat err.txt
    echo "::error::cannot read the current \"latest\" release"
    exit 1
fi

v2line=$(printf '%s\n' "$body" | grep -m 1 '^Decoder ' || true)
basicline=$(printf '%s\n' "$body" | grep -m 1 '^Legacy `basic` ' || true)

case $branch in
v2)
    prev=$(printf '%s' "$title" | sed -n 's/^Decoder //p')
    title="Decoder $ver"
    v2line="Decoder $ver (\`$short\`): \`decoder-v2.apk\`, the current version, recommended"
    if [ "$mapping" = 1 ]; then
        v2line="$v2line; \`decoder-v2-mapping.txt\` is its R8 mapping."
    else
        v2line="$v2line."
    fi
    ;;
basic)
    prev=$(printf '%s' "$basicline" | sed -n 's/^Legacy `basic` \([^ ]*\) .*/\1/p')
    [ -n "$title" ] || title="Decoder"
    basicline="Legacy \`basic\` $ver (\`$short\`): \`decoder.apk\`, the original app."
    ;;
*)
    echo "::error::unknown branch $branch"
    exit 1
    ;;
esac
echo "previous $branch version: ${prev:-none}, now $ver"
[ "$prev" = "$ver" ] || echo "new=true" >> "${GITHUB_OUTPUT:-/dev/null}"

{
    [ -z "$v2line" ] || printf '%s\n' "$v2line"
    [ -z "$basicline" ] || printf '%s\n' "$basicline"
    printf '\nChanges: [CHANGELOG.md](%s/blob/v2/CHANGELOG.md); installation: [README](%s#readme).\n' "$url" "$url"
} > notes.md

if [ "$exists" = 0 ]; then
    # a "latest" tag left without its release would keep its old commit under --target
    if [ "$branch" = v2 ]; then
        gh api -X PATCH "repos/${GITHUB_REPOSITORY}/git/refs/tags/latest" -f sha="$GITHUB_SHA" -F force=true \
            >/dev/null 2>&1 || true
    fi
    gh release create latest "$@" --latest --target "$GITHUB_SHA" \
        --title "$title" --notes-file notes.md
else
    gh release upload latest "$@" --clobber
    # a SHA256SUMS from before #236: removed if the release still has one; a failure stops here
    if gh release view latest --json assets -q '.assets[].name' | grep -qx SHA256SUMS; then
        gh release delete-asset latest SHA256SUMS --yes
    fi
    if [ "$branch" = v2 ] && [ "$mapping" = 0 ]; then
        gh release delete-asset latest decoder-v2-mapping.txt --yes 2>/dev/null || true
    fi
    if [ "$branch" = v2 ]; then
        gh api -X PATCH "repos/${GITHUB_REPOSITORY}/git/refs/tags/latest" -f sha="$GITHUB_SHA" -F force=true >/dev/null
    fi
    gh release edit latest --title "$title" --notes-file notes.md --latest
fi
rm -f notes.md err.txt
