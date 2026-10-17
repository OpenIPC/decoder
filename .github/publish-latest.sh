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
#   - SHA256SUMS is computed again over all the release's files.
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
    v2line="Decoder $ver (\`$short\`): \`decoder-v2.apk\`, the current version, recommended; \`decoder-v2-mapping.txt\` is its R8 mapping."
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
    printf '\nCheck: `sha256sum -c SHA256SUMS`. Changes: [CHANGELOG.md](%s/blob/v2/CHANGELOG.md); installation: [README](%s#readme).\n' "$url" "$url"
} > notes.md

if [ "$exists" = 0 ]; then
    gh release create latest "$@" --latest --target "$GITHUB_SHA" --title "$title" --notes-file notes.md
else
    gh release upload latest "$@" --clobber
    if [ "$branch" = v2 ]; then
        gh api -X PATCH "repos/${GITHUB_REPOSITORY}/git/refs/tags/latest" -f sha="$GITHUB_SHA" -F force=true >/dev/null
    fi
    gh release edit latest --title "$title" --notes-file notes.md --latest
fi

# SHA256SUMS over everything the release holds now
sums=$(mktemp -d)
gh release download latest --dir "$sums" --pattern 'decoder*'
(cd "$sums" && sha256sum decoder* > SHA256SUMS && sha256sum -c SHA256SUMS)
gh release upload latest "$sums/SHA256SUMS" --clobber
rm -rf "$sums" notes.md err.txt
