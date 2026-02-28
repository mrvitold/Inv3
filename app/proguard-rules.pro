# Google Sign-In / Play Services Auth
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }

# Keep ML Kit and CameraX related classes
-keep class com.google.mlkit.** { *; }
-keep class androidx.camera.** { *; }

# Keep Hilt/Dagger generated code
-dontwarn dagger.**
-dontwarn javax.annotation.**

# Firebase Crashlytics
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception
-keep class com.google.firebase.crashlytics.** { *; }
-dontwarn com.google.firebase.crashlytics.**

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Apache POI - Keep classes we use for Excel export
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-dontwarn org.apache.poi.**

# Log4j - Keep from obfuscation (POI transitive dep; uses reflection for FlowMessageFactory)
# Without this, R8 obfuscates Log4j internals and causes NoSuchMethodException at runtime
-keep class org.apache.logging.log4j.** { *; }

# Apache POI - Suppress warnings for desktop Java classes not available on Android
-dontwarn aQute.bnd.annotation.baseline.BaselineIgnore
-dontwarn aQute.bnd.annotation.spi.ServiceConsumer
-dontwarn aQute.bnd.annotation.spi.ServiceProvider
-dontwarn edu.umd.cs.findbugs.annotations.Nullable
-dontwarn edu.umd.cs.findbugs.annotations.SuppressFBWarnings
-dontwarn java.awt.Color
-dontwarn java.awt.color.ColorSpace
-dontwarn java.awt.geom.AffineTransform
-dontwarn java.awt.geom.Dimension2D
-dontwarn java.awt.geom.Path2D
-dontwarn java.awt.geom.PathIterator
-dontwarn java.awt.geom.Point2D
-dontwarn java.awt.geom.Rectangle2D$Double
-dontwarn java.awt.geom.Rectangle2D
-dontwarn java.awt.image.BufferedImage
-dontwarn java.awt.image.ColorModel
-dontwarn java.awt.image.ComponentColorModel
-dontwarn java.awt.image.DirectColorModel
-dontwarn java.awt.image.IndexColorModel
-dontwarn java.awt.image.PackedColorModel
-dontwarn javax.xml.stream.Location
-dontwarn javax.xml.stream.XMLStreamException
-dontwarn javax.xml.stream.XMLStreamReader
-dontwarn net.sf.saxon.Configuration
-dontwarn net.sf.saxon.dom.DOMNodeWrapper
-dontwarn net.sf.saxon.om.Item
-dontwarn net.sf.saxon.om.NamespaceUri
-dontwarn net.sf.saxon.om.NodeInfo
-dontwarn net.sf.saxon.om.Sequence
-dontwarn net.sf.saxon.om.SequenceTool
-dontwarn net.sf.saxon.sxpath.IndependentContext
-dontwarn net.sf.saxon.sxpath.XPathDynamicContext
-dontwarn net.sf.saxon.sxpath.XPathEvaluator
-dontwarn net.sf.saxon.sxpath.XPathExpression
-dontwarn net.sf.saxon.sxpath.XPathStaticContext
-dontwarn net.sf.saxon.sxpath.XPathVariable
-dontwarn net.sf.saxon.tree.wrapper.VirtualNode
-dontwarn net.sf.saxon.value.DateTimeValue
-dontwarn net.sf.saxon.value.GDateValue
-dontwarn org.apache.batik.anim.dom.SAXSVGDocumentFactory
-dontwarn org.apache.batik.bridge.BridgeContext
-dontwarn org.apache.batik.bridge.DocumentLoader
-dontwarn org.apache.batik.bridge.GVTBuilder
-dontwarn org.apache.batik.bridge.UserAgent
-dontwarn org.apache.batik.bridge.UserAgentAdapter
-dontwarn org.apache.batik.util.XMLResourceDescriptor
-dontwarn org.osgi.framework.Bundle
-dontwarn org.osgi.framework.BundleContext
-dontwarn org.osgi.framework.FrameworkUtil
-dontwarn org.osgi.framework.ServiceReference
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Apache POI / XMLBeans - Additional missing classes (JavaParser, Maven, Ant, Saxon, etc.)
-dontwarn com.github.javaparser.ParseResult
-dontwarn com.github.javaparser.ParserConfiguration$LanguageLevel
-dontwarn com.github.javaparser.ParserConfiguration
-dontwarn com.github.javaparser.ast.CompilationUnit
-dontwarn com.github.javaparser.ast.Node
-dontwarn com.github.javaparser.ast.NodeList
-dontwarn com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
-dontwarn com.github.javaparser.ast.body.MethodDeclaration
-dontwarn com.github.javaparser.ast.body.Parameter
-dontwarn com.github.javaparser.ast.body.TypeDeclaration
-dontwarn com.github.javaparser.ast.expr.SimpleName
-dontwarn com.github.javaparser.ast.type.PrimitiveType
-dontwarn com.github.javaparser.ast.type.ReferenceType
-dontwarn com.github.javaparser.ast.type.Type
-dontwarn com.github.javaparser.ast.type.TypeParameter
-dontwarn com.github.javaparser.resolution.MethodUsage
-dontwarn com.github.javaparser.resolution.SymbolResolver
-dontwarn com.github.javaparser.resolution.TypeSolver
-dontwarn com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration
-dontwarn com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration
-dontwarn com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration
-dontwarn com.github.javaparser.resolution.types.ResolvedType
-dontwarn com.github.javaparser.symbolsolver.JavaSymbolSolver
-dontwarn com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver
-dontwarn com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
-dontwarn com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver
-dontwarn com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
-dontwarn com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
-dontwarn com.github.javaparser.utils.CollectionStrategy
-dontwarn com.github.javaparser.utils.ProjectRoot
-dontwarn com.github.javaparser.utils.SourceRoot
-dontwarn com.sun.org.apache.xml.internal.resolver.CatalogManager
-dontwarn com.sun.org.apache.xml.internal.resolver.tools.CatalogResolver
-dontwarn java.awt.Shape
-dontwarn javax.xml.stream.XMLEventFactory
-dontwarn javax.xml.stream.XMLInputFactory
-dontwarn javax.xml.stream.XMLOutputFactory
-dontwarn javax.xml.stream.XMLStreamWriter
-dontwarn javax.xml.stream.util.StreamReaderDelegate
-dontwarn net.sf.saxon.dom.DocumentWrapper
-dontwarn net.sf.saxon.dom.NodeOverNodeInfo
-dontwarn net.sf.saxon.lib.ConversionRules
-dontwarn net.sf.saxon.ma.map.HashTrieMap
-dontwarn net.sf.saxon.om.GroundedValue
-dontwarn net.sf.saxon.om.StructuredQName
-dontwarn net.sf.saxon.query.DynamicQueryContext
-dontwarn net.sf.saxon.query.StaticQueryContext
-dontwarn net.sf.saxon.query.XQueryExpression
-dontwarn net.sf.saxon.str.StringView
-dontwarn net.sf.saxon.str.UnicodeString
-dontwarn net.sf.saxon.type.BuiltInAtomicType
-dontwarn net.sf.saxon.type.ConversionResult
-dontwarn net.sf.saxon.value.AnyURIValue
-dontwarn net.sf.saxon.value.AtomicValue
-dontwarn net.sf.saxon.value.BigDecimalValue
-dontwarn net.sf.saxon.value.BigIntegerValue
-dontwarn net.sf.saxon.value.BooleanValue
-dontwarn net.sf.saxon.value.CalendarValue
-dontwarn net.sf.saxon.value.DateValue
-dontwarn net.sf.saxon.value.DoubleValue
-dontwarn net.sf.saxon.value.DurationValue
-dontwarn net.sf.saxon.value.FloatValue
-dontwarn net.sf.saxon.value.GDayValue
-dontwarn net.sf.saxon.value.GMonthDayValue
-dontwarn net.sf.saxon.value.GMonthValue
-dontwarn net.sf.saxon.value.GYearMonthValue
-dontwarn net.sf.saxon.value.GYearValue
-dontwarn net.sf.saxon.value.HexBinaryValue
-dontwarn net.sf.saxon.value.Int64Value
-dontwarn net.sf.saxon.value.ObjectValue
-dontwarn net.sf.saxon.value.QNameValue
-dontwarn net.sf.saxon.value.SaxonDuration
-dontwarn net.sf.saxon.value.SaxonXMLGregorianCalendar
-dontwarn net.sf.saxon.value.StringValue
-dontwarn net.sf.saxon.value.TimeValue
-dontwarn org.apache.maven.model.Resource
-dontwarn org.apache.maven.plugin.AbstractMojo
-dontwarn org.apache.maven.plugin.MojoExecutionException
-dontwarn org.apache.maven.plugin.MojoFailureException
-dontwarn org.apache.maven.plugin.logging.Log
-dontwarn org.apache.maven.plugins.annotations.LifecyclePhase
-dontwarn org.apache.maven.plugins.annotations.Mojo
-dontwarn org.apache.maven.plugins.annotations.Parameter
-dontwarn org.apache.maven.project.MavenProject
-dontwarn org.apache.tools.ant.BuildException
-dontwarn org.apache.tools.ant.DirectoryScanner
-dontwarn org.apache.tools.ant.FileScanner
-dontwarn org.apache.tools.ant.Project
-dontwarn org.apache.tools.ant.taskdefs.Jar
-dontwarn org.apache.tools.ant.taskdefs.Javac
-dontwarn org.apache.tools.ant.taskdefs.MatchingTask
-dontwarn org.apache.tools.ant.types.FileSet
-dontwarn org.apache.tools.ant.types.Path$PathElement
-dontwarn org.apache.tools.ant.types.Path
-dontwarn org.apache.tools.ant.types.Reference

# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile