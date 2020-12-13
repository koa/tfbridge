#!/usr/bin/env bash
#docker build . -t koa1/tfbridge:snapshot
#docker push koa1/tfbridge:snapshot
version=$(date "+%Y%m%d-%H%M%S")
mvn -Dlocal.version=$version clean install jib:build || exit 1
#docker push docker-snapshot.berg-turbenthal.ch/tfbridge:$version
kubectl --kubeconfig=$HOME/.kube/config-sr08 -n home set image deployment/tfbridge tfbridge=docker-snapshot.berg-turbenthal.ch/tfbridge:$version
kubectl --kubeconfig=$HOME/.kube/config-sr08 -n home rollout status deployment/tfbridge
#kubectl apply -f deployment.yaml
#kubectl -n prod delete pod -l app=tfbridge
