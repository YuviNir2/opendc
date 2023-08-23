package org.opendc.experiments.rsuv

import com.fasterxml.jackson.dataformat.csv.CsvFactory
import com.fasterxml.jackson.dataformat.csv.CsvParser
import com.fasterxml.jackson.dataformat.csv.CsvSchema
import java.io.File



val baseDir: File = File("src/test/resources")

val factory = CsvFactory()
    .enable(CsvParser.Feature.ALLOW_COMMENTS)
    .enable(CsvParser.Feature.TRIM_SPACES)

val numPlayersSchema = CsvSchema.builder()
    .addColumn("timestamp", CsvSchema.ColumnType.NUMBER)
    .addColumn("avgPlayerCount", CsvSchema.ColumnType.NUMBER)
    .setAllowComments(true)
    .setUseHeader(true)
    .build()


val configSchema = CsvSchema.builder()
    .addColumn("envFileName", CsvSchema.ColumnType.STRING)
    .addColumn("traceFileName", CsvSchema.ColumnType.STRING)
    .addColumn("singlePlayerCpuUsage", CsvSchema.ColumnType.NUMBER)
    .addColumn("maxCoresPerVm", CsvSchema.ColumnType.NUMBER)
    .addColumn("singlePlayerNetworkUsage", CsvSchema.ColumnType.NUMBER)
    .addColumn("maxNicsPerVm", CsvSchema.ColumnType.NUMBER)
    .addColumn("maxNumPlayersPerVm", CsvSchema.ColumnType.NUMBER)
    .addColumn("vmMemoryCapacity", CsvSchema.ColumnType.NUMBER)
    .addColumn("cpuMaxPower", CsvSchema.ColumnType.NUMBER)
    .addColumn("cpuIdlePower", CsvSchema.ColumnType.NUMBER)
    .addColumn("cpuPowerModel", CsvSchema.ColumnType.STRING)
    .addColumn("nicMaxPower", CsvSchema.ColumnType.NUMBER)
    .addColumn("nicIdlePower", CsvSchema.ColumnType.NUMBER)
    .addColumn("nicPowerModel", CsvSchema.ColumnType.STRING)
    .addColumn("gameType", CsvSchema.ColumnType.STRING)
    .addColumn("dataRate", CsvSchema.ColumnType.STRING)
    .setAllowComments(true)
    .setUseHeader(true)
    .build()


enum class GameType {
    TBS, FPS, MMORPG
}

enum class DataRateLevel {
    Linear, Square, Cubic
}
