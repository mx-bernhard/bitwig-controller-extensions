#!/usr/bin/env bash

# location of script
SCRIPT_DIR=$(dirname $(realpath $0))
echo $SCRIPT_DIR
cd $SCRIPT_DIR
changelog_file="../CHANGELOG.md"
set -e # exit on error

changelog="$(cat $changelog_file)"
changelog="$(echo "$changelog" | tail -n +7)"
echo "$changelog" > $changelog_file

# add fake version info via package.json to make conventional-changelog work like this was an npm
# package
echo "{ \"version\": \"${1}\", \"dependencies\": { \"conventional-changelog-cli\": \"^3.0.0\" } }" > package.json

corepack enable
yarn install
yarn node $(yarn bin conventional-changelog) -p angular -i $changelog_file -s -r 1 --output-unreleased=true

# prepend the # Changelog section

changelog="$(cat $changelog_file)"

preamble=$(cat << EOF
# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

EOF
)

echo "$preamble" > $changelog_file
echo "" >> $changelog_file
echo "$changelog" >> $changelog_file

# remove the fake package.json again
rm package.json