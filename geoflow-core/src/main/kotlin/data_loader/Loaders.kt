package data_loader

import com.linuxense.javadbf.DBFReader
import com.univocity.parsers.csv.CsvParser
import com.univocity.parsers.csv.CsvParserSettings
import database.enums.LoaderType
import database.extensions.queryFirstOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import mapToArray
import mu.KotlinLogging
import org.apache.poi.ss.usermodel.*
import org.postgresql.copy.CopyManager
import org.postgresql.jdbc.PgConnection
import requireNotEmpty
import java.io.File
import java.io.InputStream
import java.math.BigDecimal
import java.sql.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlin.math.floor

const val defaultDelimiter = ','
private val logger = KotlinLogging.logger {}
/** Map of jdbc type constants to the name they represent */
private val jdbcTypeNames = Types::class.java.fields.associate { (it.get(null) as Int) to it.name  }

/**
 * Obtain the Postgresql COPY command for the specified [tableName] through a stream with various format options. The
 * byte stream will always be CSV file like with a specified [delimiter] and a possible [header] line. There is also
 * an option for non-qualified files where the QUOTE nad ESCAPE options are not set.
 */
private fun getCopyCommand(
    tableName: String,
    header: Boolean,
    columnNames: List<String>,
    delimiter: Char = defaultDelimiter,
    qualified: Boolean = true,
) = """
    COPY ${tableName.lowercase()} (${columnNames.joinToString()})
    FROM STDIN
    WITH (
        FORMAT csv,
        DELIMITER '$delimiter',
        HEADER $header
        ${if (qualified) ", QUOTE '\"', ESCAPE '\"'" else ""}
    )
""".trimIndent()

/** Accepts a nullable object and formats the value to string. Most of the formatting is for Date-like types */
private fun formatObject(value: Any?): String {
    return when(value) {
        null -> ""
        is Boolean -> if (value) "TRUE" else "FALSE"
        is String -> value
        is BigDecimal -> value.toPlainString()
        is ByteArray -> value.decodeToString()
        is Timestamp -> value.toInstant().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME)
        is Instant -> value.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME)
        is LocalDateTime -> value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        is LocalDate -> value.format(DateTimeFormatter.ISO_LOCAL_DATE)
        is LocalTime -> value.format(DateTimeFormatter.ISO_LOCAL_TIME)
        is Date -> value.toInstant().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME)
        is Time -> value.toLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME)
        else -> value.toString()
    }
}

/** Converts an array of String values to a ByteArray that reflects a CSV record. Used to pipe output to COPY command */
private fun recordToCsvBytes(record: Array<String>): ByteArray {
    return record.joinToString(separator = "\",\"", prefix = "\"", postfix = "\"\n") { value ->
        value.replace("\"", "\"\"")
    }.toByteArray()
}

/** Transforms a source column name to a valid PostgreSQL column name */
private fun formatColumnName(name: String): String {
    return name.trim()
        .replace("#", "NUM")
        .replace("\\s+".toRegex(), "_")
        .uppercase()
        .replace("\\W".toRegex(), "")
        .replace("^\\d".toRegex()) { "_${it.value}" }
        .take(60)
}

/**
 * Utility function to use a parser like a closable object.
 *
 * Performs the suspending [block] wrapped in a  try-catch-finally with the given context of a [CsvParser]. Function
 * starts parsing before the [block] and always stops the parser before function exit in finally block.
 */
private suspend fun <T> CsvParser.use(file: File, block: suspend CsvParser.(CsvParser) -> T): T {
    try {
        beginParsing(file)
        return block(this)
    } catch (e: Throwable) {
        throw e
    } finally {
        stopParsing()
    }
}

/** Analyzes records for files without defined column types. Calls [analyzeRecords] with the default type of VARCHAR */
private fun analyzeNonTypedRecords(
    tableName: String,
    header: List<String>,
    records: List<Array<String>>,
): AnalyzeResult {
    return analyzeRecords(
        tableName,
        header.map { it to "VARCHAR" },
        records,
    )
}

/**
 * Analyzes a [records] chunk given a [tableName] and [header] list, returning an [AnalyzeResult].
 *
 * The result is obtained by using passed data and looking into each column and finding the max and min string lengths.
 */
private fun analyzeRecords(
    tableName: String,
    header: List<Pair<String, String>>,
    records: List<Array<String>>,
): AnalyzeResult {
    require(records.isNotEmpty()) { "Records to analyze cannot be empty" }
    require(header.size == records.first().size) { "First record size must match header size" }
    val recordCount = records.size
    val stats = header.mapIndexed { index, (name, type) ->
        val lengths = records.map { record ->
            record[index].length
        }.sorted()
        ColumnStats(
            name = name,
            maxLength = lengths.last(),
            minLength = lengths.first(),
            type = type,
            index = index,
        )
    }
    return AnalyzeResult(tableName, recordCount, stats)
}

/** Running SQL query to find out if the [tableName] can be found in the given [schema] */
fun Connection.checkTableExists(tableName: String, schema: String = "public"): Boolean {
    return prepareStatement("""
        select table_name
        from   information_schema.tables
        where  table_schema = ?
        and    table_name = ?
    """.trimIndent()).use { statement ->
        statement.setString(1, schema)
        statement.setString(2, tableName.lowercase())
        statement.executeQuery().use { rs ->
            rs.next()
        }
    }
}

private fun Connection.getDefaultDataColumnNames(tableName: String): String {
    val sql = """
        select string_agg(column_name, ',' order by ordinal_position) "columns"
        from   information_schema.columns
        where  table_name = ?
        and    is_identity = 'NO'
        group by table_name;
    """.trimIndent()
    return queryFirstOrNull(sql = sql, tableName) ?: throw IllegalArgumentException("Table does not exist")
}

fun Connection.loadDefaultData(tableName: String, inputStream: InputStream): Long {
    return CopyManager(this.unwrap(PgConnection::class.java))
        .copyIn(
            getCopyCommand(
                tableName = tableName,
                header = true,
                columnNames = getDefaultDataColumnNames(tableName).split(','),
            ),
            inputStream
        )
}

/**
 * Loads a given [file] into the Connection, if the file type is supported.
 *
 * Checks some assumptions (see Throws), creates a [CopyManager] and then calls the appropriate extension function to
 * load the given file type. Loading is performed by reading and transforming each file's record into a [ByteArray] to
 * stream those bytes to the Connection server. For more details or loading requirement per [LoaderType] see the
 * appropriate loader functions.
 *
 * @throws IllegalArgumentException various cases:
 * - [file] does not exist
 * - [file] provided is not a file
 * - [loaders] is empty
 * - [LoaderType] cannot be found
 */
suspend fun Connection.loadFile(
    file: File,
    loaders: List<LoadingInfo>,
) {
    require(file.exists()) { "File cannot be found" }
    require(file.isFile) { "File object provided is not a file in the directory system" }
    requireNotEmpty(loaders) { "Loaders cannot be empty" }
    val loaderType = LoaderType.getLoaderTypeFromExtension(file.extension)
    with(CopyManager(this.unwrap(PgConnection::class.java))) {
        when(loaderType) {
            LoaderType.Excel -> {
                loadExcelFile(
                    excelFile = file,
                    loaders = loaders,
                )
            }
            LoaderType.Flat -> {
                loadFlatFile(
                    flatFile = file,
                    loader = loaders.first(),
                )
            }
            LoaderType.MDB -> {
                loadMdbFile(
                    mdbFile = file,
                    loaders = loaders,
                )
            }
            LoaderType.DBF -> {
                loadDbfFile(
                    dbfFile = file,
                    loader = loaders.first(),
                )
            }
        }
    }
}

/**
 * Returns a Flow that emits [AnalyzeResult] instances for a given [file] if the file type is supported.
 *
 * Checks some assumptions (see Throws) then calls the appropriate extension function to analyze the given file type.
 * Analyzing is performed by reading and transforming each file's records into chunks then provides metadata and stats
 * on the file's columns. For more details or analyzing requirement per [LoaderType] see the appropriate analysis
 * functions.
 *
 * Since files can contain multiple sub tables, those analysis functions are an extension of [FlowCollector] in order
 * to process and emit results directly within the function.
 *
 * @throws IllegalArgumentException various cases:
 * - [file] does not exist
 * - [file] provided is not a file
 * - [analyzers] is empty
 * - [LoaderType] cannot be found
 */
suspend fun analyzeFile(
    file: File,
    analyzers: List<AnalyzeInfo>,
): Flow<AnalyzeResult> {
    require(file.exists()) { "File cannot be found" }
    require(file.isFile) { "File object provided is not a file in the directory system" }
    requireNotEmpty(analyzers) { "analyzers cannot be empty" }
    val loaderType = LoaderType.getLoaderTypeFromExtension(file.extension)
    return flow {
        when(loaderType) {
            LoaderType.Excel -> {
                analyzeExcelFile(
                    excelFile = file,
                    analyzers = analyzers,
                )
            }
            LoaderType.Flat -> {
                val result = analyzeFlatFile(
                    flatFile = file,
                    analyzer = analyzers.first(),
                )
                emit(result)
            }
            LoaderType.MDB -> {
                analyzeMdbFile(
                    mdbFile = file,
                    analyzers = analyzers,
                )
            }
            LoaderType.DBF -> {
                val result = analyzeDbfFile(
                    dbfFile = file,
                    analyzer = analyzers.first(),
                )
                emit(result)
            }
        }
    }
}

/**
 * Uses a [CsvParser] to parse flat file into records and analyze the resulting columns.
 *
 * Generates a chunked sequence of 10000 records per chunk to analyze and reduce to a single [AnalyzeResult]. Requires
 * that standard flat file properties are provided.
 */
private suspend fun analyzeFlatFile(
    flatFile: File,
    analyzer: AnalyzeInfo,
): AnalyzeResult {
    val parserSettings = CsvParserSettings().apply {
        format.delimiter = analyzer.delimiter
        format.quote = if (analyzer.qualified) '"' else '\u0000'
        format.quoteEscape = format.quote
    }
    return CsvParser(parserSettings).use(flatFile) { parser ->
        val header = parser.parseNext().map { formatColumnName(it) }
        generateSequence { parser.parseNext() }
            .chunked(10000)
            .asFlow()
            .flowOn(Dispatchers.IO)
            .map { recordChunk -> analyzeNonTypedRecords(analyzer.tableName, header, recordChunk) }
            .reduce { acc, analyzeResult -> acc.merge(analyzeResult) }
    }
}

/**
 * Extension function uses a [loader] to allow for a CopyManager to load a [flatFile] line by line to a given table.
 *
 * Creates a [CopyIn][org.postgresql.copy.CopyIn] instance then utilizes the provided stream to write each line of the
 * file to the Connection of the CopyManager.
 */
private suspend fun CopyManager.loadFlatFile(
    flatFile: File,
    loader: LoadingInfo,
) {
    val copyStream = copyIn(
        getCopyCommand(
            tableName = loader.tableName,
            header = true,
            delimiter = loader.delimiter,
            qualified = loader.qualified,
            columnNames = loader.columns,
        )
    )
    flatFile
        .bufferedReader()
        .useLines { lines ->
            lines.asFlow()
                .flowOn(Dispatchers.IO)
                .map { line -> "$line\n".toByteArray() }
                .collect {
                    copyStream.writeToCopy(it, 0, it.size)
                }
        }
    val recordCount = copyStream.endCopy()
    logger.info("Copy stream closed. Wrote $recordCount records to the target table ${loader.tableName}")
}

/**
 * Extension function to extract a sequence of records from an Excel [Sheet].
 *
 * Uses the sheet's [row iterator][Sheet.rowIterator] to traverse the sheet and collect each row's cells as String.
 * Since a cell can have many types, be a formula or be null, we apply a transformation to extract the cell value and
 * convert that value to a String.
 */
private fun Sheet.excelSheetRecords(
    headerLength: Int,
    evaluator: FormulaEvaluator,
    formatter: DataFormatter,
): Sequence<Array<String>> {
    val iterator = rowIterator()
    iterator.next()
    return iterator
        .asSequence()
        .map { row ->
            0.until(headerLength)
                .map { row.getCell(it, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK) }
                .map { cell ->
                    val cellValue = evaluator.evaluate(cell) ?: CellValue("")
                    when (cellValue.cellType) {
                        null -> ""
                        CellType.NUMERIC -> {
                            val numValue = cellValue.numberValue
                            when {
                                DateUtil.isCellDateFormatted(cell) ->
                                    cell.localDateTimeCellValue.format(DateTimeFormatter.ISO_LOCAL_DATE)
                                floor(numValue) == numValue -> numValue.toLong().toString()
                                else -> numValue.toString()
                            }
                        }
                        CellType.STRING -> cellValue.stringValue ?: ""
                        CellType.BLANK -> ""
                        CellType.BOOLEAN -> if (cellValue.booleanValue) "TRUE" else "FALSE"
                        CellType._NONE, CellType.ERROR -> formatter.formatCellValue(cell)
                        else -> ""
                    }.trim()
                }
                .toList()
                .toTypedArray()
        }
}

/**
 * Extension function to analyze all the required sheets (as per [analyzers]).
 *
 * Generates a chunked sequence of 10000 records per chunk to analyze and reduce to a single [AnalyzeResult].
 */
private suspend fun FlowCollector<AnalyzeResult>.analyzeExcelFile(
    excelFile: File,
    analyzers: List<AnalyzeInfo>,
) {
    excelFile.inputStream().use { inputStream ->
        withContext(Dispatchers.IO) {
            @Suppress("BlockingMethodInNonBlockingContext")
            WorkbookFactory.create(inputStream)
        }.use { workbook ->
            val formulaEvaluator = workbook.creationHelper.createFormulaEvaluator()
            val dataFormatter = DataFormatter()
            for (info in analyzers) {
                val sheet: Sheet = workbook.getSheet(info.subTable)
                    ?: throw IllegalStateException("Could not find sheet")
                val header = sheet.first().cellIterator().asSequence().map { cell ->
                    formatColumnName(cell.stringCellValue)
                }.toList()
                val analyzeResult = sheet.excelSheetRecords(header.size, formulaEvaluator, dataFormatter)
                    .chunked(10000)
                    .asFlow()
                    .flowOn(Dispatchers.IO)
                    .map { recordChunk -> analyzeNonTypedRecords(info.tableName, header, recordChunk) }
                    .reduce { acc, analyzeResult -> acc.merge(analyzeResult) }
                emit(analyzeResult)
            }
        }
    }
}

/**
 * Extension function allows for a CopyManager to easily load a [file][excelFile] to each table using the linked sheet.
 *
 * Creates a [CopyIn][org.postgresql.copy.CopyIn] instance for each sheet then utilizes the provided stream to write
 * each record of the sheet to the Connection of the CopyManager.
 */
private suspend fun CopyManager.loadExcelFile(
    excelFile: File,
    loaders: List<LoadingInfo>,
) {
    excelFile.inputStream().use { inputStream ->
        withContext(Dispatchers.IO) {
            @Suppress("BlockingMethodInNonBlockingContext")
            WorkbookFactory.create(inputStream)
        }.use { workbook ->
            val formulaEvaluator = workbook.creationHelper.createFormulaEvaluator()
            val dataFormatter = DataFormatter()
            for (loader in loaders) {
                val sheet = workbook.getSheet(loader.subTable!!)
                val copyStream = copyIn(
                    getCopyCommand(
                        tableName = loader.tableName,
                        header = true,
                        columnNames = loader.columns,
                    )
                )
                val headerLength = sheet.first().physicalNumberOfCells
                sheet.excelSheetRecords(headerLength, formulaEvaluator, dataFormatter)
                    .asFlow()
                    .flowOn(Dispatchers.IO)
                    .map { record -> recordToCsvBytes(record) }
                    .collect {
                        copyStream.writeToCopy(it, 0, it.size)
                    }
                val recordCount = copyStream.endCopy()
                logger.info(
                    "Copy stream closed. Wrote $recordCount records to the target table ${loader.tableName}"
                )
            }
        }
    }
}

/**
 * Extension function to yield rows as an array of Strings
 */
private fun ResultSet.resultRecords() = sequence {
    while (next()) {
        val row = mutableListOf<String>()
        for (i in 1..metaData.columnCount) {
            row += formatObject(getObject(i)).replace("\"", "\"\"")
        }
        yield(row.toTypedArray())
    }
}

/**
 * Extension function to analyze all the required sub tables (as per [analyzers]).
 *
 * Generates a chunked sequence of 10000 records per chunk to analyze and reduce to a single [AnalyzeResult].
 */
private suspend fun FlowCollector<AnalyzeResult>.analyzeMdbFile(
    mdbFile: File,
    analyzers: List<AnalyzeInfo>,
) {
    DriverManager.getConnection("jdbc:ucanaccess://${mdbFile.absolutePath}").use { connection ->
        for (info in analyzers) {
            connection.prepareStatement("SELECT * FROM ${info.subTable}").use { statement ->
                statement.executeQuery().use { rs ->
                    val headers = (1..rs.metaData.columnCount).map {
                        Pair(
                            formatColumnName(rs.metaData.getColumnName(it)),
                            jdbcTypeNames.getOrDefault(rs.metaData.getColumnType(it), "")
                        )
                    }
                    val analyzeResult = rs.resultRecords()
                        .chunked(10000)
                        .asFlow()
                        .flowOn(Dispatchers.IO)
                        .map { records -> analyzeRecords(info.tableName, headers, records) }
                        .reduce { acc, analyzeResult -> acc.merge(analyzeResult) }
                    emit(analyzeResult)
                }
            }
        }
    }
}

/**
 * Extension function allows for a CopyManager to easily load a [file][mdbFile] to each table using the linked sub
 * tables.
 *
 * Creates a [CopyIn][org.postgresql.copy.CopyIn] instance for each sub table then utilizes the provided stream to write
 * each record of the sub table to the Connection of the CopyManager.
 */
private suspend fun CopyManager.loadMdbFile(
    mdbFile: File,
    loaders: List<LoadingInfo>,
) {
    DriverManager
        .getConnection("jdbc:ucanaccess://${mdbFile.absolutePath}").use { mdbConnection ->
            for (loader in loaders) {
                val copyStream = copyIn(
                    getCopyCommand(
                        tableName = loader.tableName,
                        header = false,
                        columnNames = loader.columns,
                    )
                )
                mdbConnection
                    .prepareStatement("SELECT * FROM ${loader.subTable!!}")
                    .executeQuery()
                    .use { rs ->
                        rs.resultRecords()
                            .asFlow()
                            .flowOn(Dispatchers.IO)
                            .map { record -> recordToCsvBytes(record) }
                            .collect {
                                copyStream.writeToCopy(it, 0, it.size)
                            }
                    }
                val recordCount = copyStream.endCopy()
                logger.info(
                    "Copy stream closed. Wrote $recordCount records to the target table ${loader.tableName}"
                )
            }
        }
}

/**
 * Extract header details from a DBF [file][dbfFile] and return as pairs. Note: avoided a Map to preserve order
 */
private fun getDbfHeader(dbfFile: File): List<Pair<String, String>> {
    return dbfFile.inputStream().use { inputStream ->
        DBFReader(inputStream).use { reader ->
            0.until(reader.fieldCount).map {
                val field = reader.getField(it)
                field.name to field.type.name
            }
        }
    }
}

/**
 * Utility Function to extract records from a DBF [file][dbfFile] as a yielded sequence of String arrays
 */
private fun dbfFileRecords(dbfFile: File) = sequence {
    dbfFile.inputStream().use { inputStream ->
        DBFReader(inputStream).use { reader ->
            for (record in generateSequence { reader.nextRecord() }) {
                yield(record.mapToArray { value -> formatObject(value) })
            }
        }
    }
}


/**
 * Extension function to analyze the provided [file][dbfFile].
 *
 * Generates a chunked sequence of 10000 records per chunk to analyze and reduce to a single [AnalyzeResult].
 */
private suspend fun analyzeDbfFile(
    dbfFile: File,
    analyzer: AnalyzeInfo,
): AnalyzeResult {
    val header = getDbfHeader(dbfFile)
    return dbfFileRecords(dbfFile)
        .chunked(10000)
        .asFlow()
        .flowOn(Dispatchers.IO)
        .map { records -> analyzeRecords(analyzer.tableName, header, records) }
        .reduce { acc, analyzeResult -> acc.merge(analyzeResult) }
}

/**
 * Extension function uses a [loader] to allow for a CopyManager to load a [file][dbfFile]line by line to a given table.
 *
 * Creates a [CopyIn][org.postgresql.copy.CopyIn] instance then utilizes the provided stream to write each record of the
 * file to the Connection of the CopyManager.
 */
private suspend fun CopyManager.loadDbfFile(
    dbfFile: File,
    loader: LoadingInfo,
) {
    val copyStream = copyIn(
        getCopyCommand(
            tableName = loader.tableName,
            header = false,
            columnNames = loader.columns,
        )
    )
    dbfFileRecords(dbfFile)
        .asFlow()
        .flowOn(Dispatchers.IO)
        .map {record -> recordToCsvBytes(record) }
        .collect {
            copyStream.writeToCopy(it, 0, it.size)
        }
    val recordCount = copyStream.endCopy()
    logger.info("Copy stream closed. Wrote $recordCount records to the target table ${loader.tableName}")
}
