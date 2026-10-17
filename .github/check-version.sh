#!/bin/sh
#
# check-version.sh — fail a pull request that changes the app or its build
# but not versionName in android/app/build.gradle.  A new version must be
# MAJOR.MINOR.PATCH without leading zeros, greater than the base one, and
# have its section in CHANGELOG.md ("## <version> — YYYY-MM-DD") that the
# base branch does not have yet.  android/ counts as app or build (its unit
# tests in android/app/src/test do not); docs, screenshots and the
# workflows in .github/ need no new version.  The format is checked only
# when the version changes, so the 2.xx versions before MAJOR.MINOR.PATCH
# stay valid until the next app change.
#
# Usage: .github/check-version.sh [BASE]   (default origin/v2)
#

set -eu

base=${1:-origin/v2}
gradle=android/app/build.gradle

ver() { sed -n 's/.*versionName "\(.*\)".*/\1/p' | head -n 1; }

new=$(ver < "$gradle")
old=$(git show "$base:$gradle" | ver)

changed=$(git diff --name-only "$base"...HEAD -- android ':(exclude)android/app/src/test')

if [ "$new" = "$old" ]; then
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

top=$(printf '%s\n%s\n' "$old" "$new" | sort -V | tail -n 1)
if [ "$top" != "$new" ]; then
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
