#!/usr/bin/env bash

set -e

git update-index --refresh
git diff-index --quiet HEAD --

VERSION="v0.1.$(git rev-list --count HEAD)"

echo "Releasing $VERSION"

docker build --tag ivarref/mikkmokk-proxy:"$VERSION" .
docker push ivarref/mikkmokk-proxy:"$VERSION"

git tag -a "$VERSION" -m "Release $VERSION"
git push --follow-tags
