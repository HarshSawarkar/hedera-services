module com.swirlds.metrics.impl {
    exports com.swirlds.metrics.impl;

    requires transitive com.swirlds.metrics.api;
    requires com.swirlds.base;
    requires static com.github.spotbugs.annotations;
}