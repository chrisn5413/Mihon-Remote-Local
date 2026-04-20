package eu.kanade.tachiyomi.source.model

// Compile-only stub — real implementation provided by Mihon at runtime.
// Must use Filter<*> so the JVM constructor signature matches Mihon's real FilterList:
//   <init>([Leu/kanade/tachiyomi/source/model/Filter;)V
class FilterList(vararg filters: Filter<*>) : List<Filter<*>> by filters.toList()
