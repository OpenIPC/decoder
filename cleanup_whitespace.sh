#!/bin/bash
# Clean trailing whitespace and ensure consistent line endings

echo "Cleaning trailing whitespace..."

# Find all text files and remove trailing whitespace
find . -type f \( -name "*.java" -o -name "*.xml" -o -name "*.gradle" -o -name "*.md" -o -name "*.txt" -o -name "*.json" -o -name "*.yml" -o -name "*.yaml" -o -name "*.toml" -o -name "*.ini" -o -name "*.cfg" -o -name "*.conf" \) -exec sed -i 's/[[:space:]]*$//' {} \;

echo "Done."
