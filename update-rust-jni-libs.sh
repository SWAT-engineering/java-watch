#!/usr/bin/env bash


set -euxo pipefail

RESOURCES="../resources/engineering/swat/watch/jni/"

cd src/main/rust


function build() {
  rustup target add $1
  cargo build --target $1
  mkdir -p "$RESOURCES/$2/"
  cp "target/$1/debug/librust_fsevents_jni.dylib" "$RESOURCES/$2/"
}

build "x86_64-apple-darwin" "macos-x64"
build "aarch64-apple-darwin" "macos-aarch64"

