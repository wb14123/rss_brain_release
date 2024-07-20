#!/bin/sh

set -e

RELEASE_VERSION=$1

if [ -z $RELEASE_VERSION ] ; then
	echo "Release version not set"
	exit 1
fi


if [[ -n $(git status -s) ]] ; then
	echo "You have uncommited files."
	exit 1
fi

rsync --delete -av --exclude .git --filter=':- .gitignore' . ../rss_brain_release

if [ $RELEASE_VERSION = 'amend' ] ; then
	echo "Amend existing commit"
	cd ../rss_brain_release
	git add -A .
	git commit --amend --no-edit
else
	echo "Rleasing version $RELEASE_VERSION"
	git tag $RELEASE_VERSION
	cd ../rss_brain_release
	git add -A .
	git commit -m "Release $RELEASE_VERSION"
	# git push origin master
fi



