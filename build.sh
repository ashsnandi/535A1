#!/bin/bash

mvn compile
mvn assembly:single
java -jar .\\target\\COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar .\\conf\\router1.conf