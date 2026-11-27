# Raw datasets (download yourself)

The training pipeline uses these files automatically when they are present here.
Without them it falls back to the project's synthetic generators (clearly labelled in the reports).

| File name expected here | Kaggle dataset | Used for |
|---|---|---|
| `PS_20174392719_1491204439457_log.csv` | PaySim, `ealaxi/paysim1` | Transaction fraud Random Forest |
| `HI-Small_Trans.csv` | IBM Transactions for AML, `ealtman2019/ibm-transactions-for-anti-money-laundering-aml` | Money-mule graph model, Isolation Forest |
| `SAML-D.csv` | SAML-D (search Kaggle for "SAML-D") | Cross-dataset test (optional) |

Download with the Kaggle website (sign in, Download, unzip here) or the Kaggle CLI, for example:

```
kaggle datasets download -d ealaxi/paysim1 -p ml-service/data/raw --unzip
```

Then retrain: `cd ml-service && python -m fraudshield_ml.train` (add `--max-rows 2000000` to cap memory on large files).
These files are large and are not included in the ZIP.
