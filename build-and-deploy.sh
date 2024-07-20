#!/bin/bash

set -e

SKIP_SBT=false

CODE_CLEAN=true

DEPLOY=true

tag=`git rev-parse --short HEAD`
if [[ -n $(git status -s) ]] ; then
	CODE_CLEAN=false
	echo "You have uncommited files. Use date as image tag instead of git commit."
	tag=`date +'%Y-%m-%d-%s'`
fi

if [ "$SKIP_SBT" = false ] ; then
	echo "Build jar ..."
	sbt clean generateGRPCCode
	if [[ -n $(git status -s) ]] && [ "$CODE_CLEAN" = true ] ; then
		echo "Code changed after build grpc files. Please check."
		exit 1
	fi
	sbt compile assembly
else
	echo "NOTE: Skip sbt build. Please make sure you've done \"sbt compile assembly\""
fi

image="docker-hosted.binwang.me:30008/rss_brain:$tag"
echo "Building image $image ..."
docker build -t $image .
echo "Pushing image ..."
docker push $image

if [ "$DEPLOY" = false ] ; then
	echo "DEPLOY is set to failse, skip deploy to Kubernetes"
	exit 0
fi


echo "Deploying iamge $image to Kubernetes ..."
echo "Deploying rss-brain-fetcher ..."
kubectl set image deployment/rss-brain-fetcher rss-brain-fetcher=$image
echo "Deployed rss-brain-fetcher"
echo "Deploying rss-brain-grpc ..."
kubectl set image deployment/rss-brain-grpc rss-brain-grpc=$image
echo "Deployed rss-brain-grpc"
