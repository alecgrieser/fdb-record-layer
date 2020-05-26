#!/bin/bash -eu

#
# create_branch.bash
#
# This source file is part of the FoundationDB open source project
#
# Copyright 2015-2018 Apple Inc. and the FoundationDB project authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

script_dir="$( dirname $0 )"
gradle_props="${script_dir}/../gradle.properties"
success=0

if [[ $# -lt 2 ]] ; then
    echo "Usage:"
    echo
    echo "   create_branch.bash <mode> <version>"
    echo
    echo "Create a branch. This has two different modes:"
    echo
    echo "  - dev: In dev mode, a branch is created off of the current commit with a name prefixed with \"fdb-record-layer-deveop\"."
    echo "      This mode should typically be used to create a branch that will include changes that will eventually be merged"
    echo "      into master in a future minor or major release. This might be done, for example, if the change requires breaking"
    echo "      an otherwise stable API. The version supplied should be the new future minor or major version."
    echo "  - patch: In patch mode, a branch is created off of a previous tag in order to cherry pick changes (typically bug fixes)"
    echo "      onto an older release. In that mode, the version number should be the previous version on which the patch branch should"
    echo "      be based. If the old version has a build version of 0 (i.e., is of the form x.y.z.0), then the new version will produce"
    echo "      new releases with versions of the form x.y.z.t, t > 0. Typically, this is desired behavior, but if for some reason a"
    echo "      patch branch is needed off of another patch, this script can be used to create builds with more components as needed."
    echo
    exit 0
fi

mode="$1"
version="$2"
version_elems=(${version//./ })

if [[ mode -eq "dev"]] ; then
    branch_version="$"
elif [[ mode -eq "patch" ]] ; then
    if [[ ${#version_elems[@]} -lt 3 ]] ; then
        echo "Version must contain at least a major, minor, and patch version."
        success=$((success + 1))
    elif [[ ${#version_elems[@]} -eq 4 ]] ; then
        tag_version="${version}"
        if [[ ${version_elems[3]} -eq 0 ]] ; then
            branch_version="${version_elems[0]}.${version_elems[1]}.${version_elems[2]}"
        else
            branch_version="${version}"
        fi
    else
        tag_version="${version}.0"
        branch_version="${version}"
    fi
else
    echo "Unknown mode $mode. The only known modes are \"dev\" and \"patch\". Cannot continue."
    success=$((success + 1))
fi

if [[ $success -eq 0 ]] ; then
    echo "Creating $mode branch for version ${branch_version} off of tag ${tag_version}."
    if [[ "${mode}" -eq "dev" ]] ; then
        branch_name="fdb-record-layer-develop-${branch_version}"
    else ; then
        branch_name="fdb-record-layer-${branch_version}"
    fi
    echo

    if ! (echo "Checkout out ${tag_version}..." && git checkout "${tag_version}" && echo ) ; then
        echo "Unable to checkout ${tag_version}. Ensure that that tag exists."
        success=$((success + 1))
    elif ! (echo "Creating branch ${branch_name}..." && git checkout -b "${branch_name}" && echo ) ; then
        echo "Unable to create branch ${branch_name}"
        success=$((success + 1))
    elif ! (echo "Fixing version in gradle.properties..." && (sed "s/^version=.*$/version=${branch_version}/g" "${gradle_props}" > "${gradle_props}.tmp")) ; then
        echo "Unable to replace version in gradle.properties"
        success=$((success + 1))
    elif ! (mv "${gradle_props}.tmp" "${gradle_props}" && echo) ; then
        echo "Unable to rewrite gradle.properties"
        success=$((success + 1))
    elif ! (echo "Committing changes" && git add "${gradle_props}" && git commit -m "Create ${branch_version} ${mode} branch" && echo) ; then
        echo "Unable to commit new changes"
        success=$((success + 1))
    fi
fi

exit $success
