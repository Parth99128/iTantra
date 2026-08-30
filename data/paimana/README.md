# PAIMANA ML data cleaning

This branch contains a conservative cleaning pipeline for the PDF-extracted PAIMANA project-monitoring CSV.

## Important
The source extraction contains many merged cells: dates, costs and alternative values are sometimes concatenated into one field. The pipeline deliberately **does not guess** which value is correct.

It:
- removes the completely empty `Notes_or_Status` column;
- normalizes whitespace in text fields;
- converts a numeric/date field only when exactly one unambiguous value is present;
- marks extraction ambiguity;
- keeps only candidate rows with a non-empty project name, at least two valid numeric/date fields, at least one valid numeric field, and no more than one raw extraction ambiguity;
- removes exact duplicate candidates;
- quarantines the remaining rows for later reconstruction from the original PDFs.

Raw input: 253,259 rows from 325 source PDF files.
Current conservative ML candidate set: 3,687 rows after one exact duplicate removal.

Run:

```bash
python clean_paimana.py
```

Outputs:
- `paimana_ml_cleaned.csv` — conservative ML candidate dataset
- `paimana_quarantine.csv` — rows needing source-PDF reconstruction
- `paimana_data_quality_report.csv` — cleaning statistics

Do not treat the candidate set as a final ground-truth dataset. For production ML, the quarantined records should be reconstructed from the original PDF tables and then validated with domain rules.
