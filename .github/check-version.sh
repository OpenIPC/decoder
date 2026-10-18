#!/bin/sh
#
# check-version.sh — fail a pull request that changes the app or its build
# but not versionName in android/app/build.gradle.  A new version must be
# MAJOR.MINOR.PATCH without leading zeros, greater than the base one, and
# have its section in CHANGELOG.md ("## <version> — YYYY-MM-DD") that the
# base branch does not have yet.  android/ (without its unit tests in
# android/app/src/test and its Markdown files) and the build workflow
# .github/workflows/build.yml count as app or build: the workflow holds
# build steps too, and the script cannot tell them from the others.  Docs,
# screenshots and the rest of .github/ need no new version.
# NO_RELEASE=true (the pull request's `no-release` label) lets a change to
# the app or the build through without a new version, for changes that
# leave the APKs as they are (a test or publication step in the workflow,
# a comment).  The format is checked only when the version changes, so the
# 2.xx versions before MAJOR.MINOR.PATCH stay valid until the next app
# change.
#
# Usage: .github/check-version.sh [BASE]   (default origin/v2; CI passes
# HEAD^1, the base side of the pull request's merge commit)
#

set -eu

base=${1:-origin/v2}
gradle=android/app/build.gradle

# The one versionName assignment (comments and other text do not count).
ver() {
	v=$(sed -n 's/^[[:space:]]*versionName[[:space:]]*"\([^"]*\)"[[:space:]]*$/\1/p')
	if [ "$(printf '%s\n' "$v" | grep -c .)" -ne 1 ]; then
		echo "::error::$1:$gradle: expected one versionName assignment," \
			"found: $(printf '%s' "$v" | tr '\n' ' ')" >&2
		exit 1
	fi
	printf '%s\n' "$v"
}

new=$(ver HEAD < "$gradle")
old=$(git show "$base:$gradle" | ver "$base")

changed=$(git diff --name-only "$base"...HEAD -- android .github/workflows/build.yml \
	':(exclude)android/app/src/test' ':(exclude,glob)**/*.md')

if [ "$new" = "$old" ]; then
	if [ -n "$changed" ] && [ "${NO_RELEASE:-}" = true ]; then
		echo "::notice::the app or its build changed, versionName stays $old: the no-release" \
			"label says the APKs do not change"
		echo "$changed"
		exit 0
	fi
	if [ -n "$changed" ]; then
		echo "::error::the app or its build changed, versionName is still $old:" \
			"bump PATCH (fix) or MINOR (feature) and add its CHANGELOG.md section"
		echo "$changed"
		exit 1
	fi
	echo "version $new unchanged, no app or build changes"
	exit 0
fi

if ! echo "$new" | grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'; then
	echo "::error::versionName \"$new\" is not MAJOR.MINOR.PATCH"
	exit 1
fi

# A 2.xx version from before MAJOR.MINOR.PATCH is X.Y.0: 2.17.0 is 2.17.
case $old in
*.*.*) oldn=$old ;;
*) oldn=$old.0 ;;
esac
top=$(printf '%s\n%s\n' "$oldn" "$new" | sort -V | tail -n 1)
if [ "$new" = "$oldn" ] || [ "$top" != "$new" ]; then
	echo "::error::versionName $new is not greater than $old"
	exit 1
fi

# $new is digits and dots only (checked above): escape the dots, require a date.
re=$(printf '%s' "$new" | sed 's/\./\\./g')
if ! grep -Eq "^## $re — [0-9]{4}-[0-9]{2}-[0-9]{2}\$" CHANGELOG.md; then
	echo "::error::CHANGELOG.md has no section \"## $new — YYYY-MM-DD\""
	exit 1
fi

# The section must come with this pull request, not be one the base
# branch already has (a version taken from there brings no notes).
if git show "$base:CHANGELOG.md" 2>/dev/null | grep -Eq "^## $re — "; then
	echo "::error::the section \"## $new\" is already on $base: take a new version"
	exit 1
fi

echo "version $old -> $new, CHANGELOG.md section found"
