nextflow.enable.dsl = 2

/*
 * RNA-seq quantification pipeline, adapted from the canonical nextflow-io/rnaseq-nf tutorial,
 * to showcase how the `workbench` executor parallelizes work: every task below becomes its own
 * Posit Workbench Job, and Nextflow submits independent tasks concurrently.
 *
 *                      +--> FASTQC (x N samples) --+
 *   reads (N samples) -+                           +--> MULTIQC
 *                      +--> QUANT  (x N samples) --+
 *                              ^
 *   transcriptome --> INDEX ---+  (one index, reused by every QUANT task)
 *
 * With the default 4 samples: INDEX and all 4 FASTQC jobs start immediately (5 concurrent
 * jobs), the 4 QUANT jobs start as soon as INDEX finishes, and MULTIQC waits for all 8
 * per-sample results (fan-in).
 */

params.dataUrl       = 'https://raw.githubusercontent.com/nextflow-io/rnaseq-nf/master/data/ggal'
params.transcriptome = "${params.dataUrl}/ggal_1_48850000_49020000.Ggal71.500bpflank.fa"
params.samples       = 'gut,liver,lung,spleen'
params.reads         = null     // optional local glob, e.g. 'data/*_{1,2}.fq' -- overrides samples
params.outdir        = 'results'

process INDEX {
    tag "${transcriptome.baseName}"
    label 'multi_core'
    container 'quay.io/biocontainers/salmon:1.10.3--h6dccd9a_2'

    input:
    path transcriptome

    output:
    path 'index'

    script:
    """
    salmon index --threads ${task.cpus} -t ${transcriptome} -i index
    """
}

process FASTQC {
    tag "${sample_id}"
    label 'single_core'
    container 'quay.io/biocontainers/fastqc:0.12.1--hdfd78af_0'

    input:
    tuple val(sample_id), path(reads)

    output:
    path "fastqc_${sample_id}_logs"

    script:
    """
    mkdir fastqc_${sample_id}_logs
    fastqc --threads ${task.cpus} --quiet --outdir fastqc_${sample_id}_logs ${reads}
    """
}

process QUANT {
    tag "${sample_id}"
    label 'multi_core'
    container 'quay.io/biocontainers/salmon:1.10.3--h6dccd9a_2'
    publishDir "${params.outdir}/quant", mode: 'copy'

    input:
    path index
    tuple val(sample_id), path(reads)

    output:
    path "${sample_id}"

    script:
    """
    salmon quant --threads ${task.cpus} --libType A -i ${index} \\
        -1 ${reads[0]} -2 ${reads[1]} -o ${sample_id}
    """
}

process MULTIQC {
    label 'single_core'
    container 'quay.io/biocontainers/multiqc:1.32--pyhdfd78af_0'
    publishDir params.outdir, mode: 'copy'

    input:
    path '*'

    output:
    path 'multiqc_report.html'

    script:
    """
    multiqc .
    """
}

workflow {
    read_pairs = params.reads
        ? Channel.fromFilePairs(params.reads, checkIfExists: true)
        : Channel.fromList(params.samples.tokenize(',')).map { s ->
              tuple(s, [file("${params.dataUrl}/ggal_${s}_1.fq"), file("${params.dataUrl}/ggal_${s}_2.fq")])
          }

    index = INDEX(params.transcriptome)

    // `index` is a single-value channel, so it's reused by every QUANT task instead of being
    // consumed by the first one -- that's what lets all samples quantify in parallel.
    quant  = QUANT(index, read_pairs)
    fastqc = FASTQC(read_pairs)

    MULTIQC(quant.mix(fastqc).collect())
}
