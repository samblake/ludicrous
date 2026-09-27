module com.github.samblake.ludicrous {
    requires java.compiler;
    requires com.squareup.javapoet;

    exports com.github.samblake.ludicrous;

    provides javax.annotation.processing.Processor with com.github.samblake.ludicrous.LudicrousProcessor;
}
