import pandas as pd, re

INPUT='extracted_paimana_post_processed.csv'
OUTPUT='paimana_ml_cleaned.csv'
QUAR='paimana_quarantine.csv'
REPORT='paimana_data_quality_report.csv'

df=pd.read_csv(INPUT, dtype=str, keep_default_na=False)
df=df.drop(columns=['Notes_or_Status'], errors='ignore')

def norm_text(x):
    return re.sub(r'\s+', ' ', str(x)).strip()

def one_number(x):
    s=str(x).replace(',', '').strip()
    vals=re.findall(r'(?<!\d)-?\d+(?:\.\d+)?(?!\d)', s)
    if len(vals)==1 and not re.search(r'[\[\]()]', s):
        return float(vals[0])
    return pd.NA

def one_date(x):
    s=str(x).strip()
    vals=re.findall(r'(?<!\d)(?:0?[1-9]|1[0-2])/(?:19|20)\d{2}(?!\d)', s)
    if len(vals)==1 and not re.search(r'[\[\]()]', s):
        return vals[0]
    return pd.NA

out=pd.DataFrame({
    'source_file':df['Source_File'].map(norm_text),
    'sl_no_raw':df['Sl_No'].map(norm_text),
    'project_name':df['Sector_or_Project_Name'].map(norm_text),
    'original_cost':df['Projects_on_Monitor_or_Original_Cost'].map(one_number),
    'anticipated_cost':df['Anticipated_Cost'].map(one_number),
    'cumulative_expenditure':df['Cumulative_Expenditure'].map(one_number),
    'original_commissioning_date':df['Original_Commissioning_Date'].map(one_date),
    'anticipated_commissioning_date':df['Anticipated_Commissioning_Date'].map(one_date),
    'delay_months':df['Delay_in_Months'].map(one_number),
})
raw_cols=[c for c in df.columns if c not in ['Source_File','Sl_No','Sector_or_Project_Name']]
out['extraction_ambiguous_count']=0
for c in raw_cols:
    out['extraction_ambiguous_count'] += df[c].astype(str).str.contains(r'[\[\]()\n]',regex=True).astype(int)
out['project_name_missing']=out['project_name'].eq('')
value_cols=['original_cost','anticipated_cost','cumulative_expenditure','original_commissioning_date','anticipated_commissioning_date','delay_months']
out['numeric_or_date_fields_valid']=out[value_cols].notna().sum(axis=1)
num_cols=['original_cost','anticipated_cost','cumulative_expenditure','delay_months']
out['numeric_fields_valid']=out[num_cols].notna().sum(axis=1)
out['is_ml_candidate']=(~out['project_name_missing']) & (out['numeric_or_date_fields_valid']>=2) & (out['numeric_fields_valid']>=1) & (out['extraction_ambiguous_count']<=1)

cand=out[out['is_ml_candidate']].copy()
pre=len(cand)
cand=cand.drop_duplicates(subset=['source_file','project_name','original_cost','anticipated_cost','cumulative_expenditure','original_commissioning_date','anticipated_commissioning_date','delay_months']).reset_index(drop=True)
quar=out[~out['is_ml_candidate']].copy()
cand.to_csv(OUTPUT,index=False)
quar.to_csv(QUAR,index=False)
report=pd.DataFrame([
 {'metric':'raw_rows','value':len(df)},
 {'metric':'raw_columns_after_empty_notes_removal','value':len(df.columns)},
 {'metric':'candidate_rows_before_dedup','value':pre},
 {'metric':'candidate_rows_after_dedup','value':len(cand)},
 {'metric':'quarantine_rows','value':len(quar)},
 {'metric':'duplicate_candidates_removed','value':pre-len(cand)},
 {'metric':'source_files','value':df['Source_File'].nunique()},
 {'metric':'empty_notes_column_removed','value':1},
 {'metric':'rule','value':'A numeric/date field is populated only when exactly one unambiguous token exists. ML candidates require a nonempty project name, at least 2 valid numeric/date fields, at least 1 valid numeric field, and no more than 1 raw extraction ambiguity. Other rows are quarantined rather than guessed.'},
])
report.to_csv(REPORT,index=False)
print(report.to_string(index=False))
