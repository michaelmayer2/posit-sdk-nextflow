nextflow.enable.dsl = 2

// Three-step pipeline (generate -> square -> sum), mirroring the sibling posit-sdk-snakemake
// integration's examples/Snakefile: two of three steps declare their own container image, the
// third falls back to the default container/no container.

process generate {
    container 'python:3.12-slim'
    cpus 1
    memory '512 MB'

    output:
    path 'numbers.txt'

    script:
    '''
    python3 -c "print('\\n'.join(str(i) for i in range(1, 6)))" > numbers.txt
    '''
}

process square {
    container 'python:3.11-slim'
    cpus 1
    memory '512 MB'

    input:
    path numbers

    output:
    path 'squares.txt'

    script:
    '''
    python3 -c "
with open('numbers.txt') as f, open('squares.txt', 'w') as out:
    for line in f:
        n = int(line.strip())
        out.write(f'{n * n}\\n')
"
    '''
}

// No `container` here -- falls back to whatever the process/executor default is.
process sum_squares {
    cpus 1
    memory '512 MB'

    input:
    path squares

    output:
    path 'sum.txt'

    script:
    '''
    python3 -c "
with open('squares.txt') as f:
    print(sum(int(l.strip()) for l in f))
" > sum.txt
    '''
}

workflow {
    numbers = generate()
    squares = square(numbers)
    sum_squares(squares)
}
