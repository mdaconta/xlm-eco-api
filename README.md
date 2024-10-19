# Welcome to the Cross-LM (XLM) Ecosystem API Project!

This project offers three key benefits:
1. Cross LLM/SLM Generative AI Operations!
2. Cross Programming Language Generative AI Operations!
3. Standardized APIs across the Complete Ecosystem of Generative AI Operations!

## Table of Contents
- [Introduction](#introduction)
- [Features](#features)
- [Installation](#installation)
- [Usage](#usage)
- [Conclusion](#conclusion)

## Introduction

Here is the LLM Ecosystem diagram from my article entitled [What is the LLM Ecosystem?](https://www.daconta.us/Articles/The-LLM-Ecosystem.html).
![The LLM Ecosystem](https://www.daconta.us/Articles/LLM-Ecosystem-Components.jpg)

## Features

This project is under active development and will change considerably over the next 
several months so make sure you check back to get the latest. 

Here is the Feature Roadmap:
1. Chat API
    1. LM Metadata
         1. Provider Name
         2. Model name
         3. Type (LLM, SLM, etc.)
         4. Max Prompt Size
         5. Knowledge Cutoff Date
         6. LM Capabilities/Profile
         7. Max Completion Token Limit
    2. Synchronous Completion
    3. Asynchronous/Streaming Completion
2. Conversation API
    1. Shared chats
        1. Chat History
        2. Chat IDs
        3. Chat encryption/Security
        4. Chat labels
        5. Chat Owner
        6. Group Chats?
    2. System Message
    3. Completion Format
        1. Code
        2. Markdown
        3. HTML
        4. Formulas
        5. Image
        6. Tables
        7. JSON
3. LM Customization API
    1. Token Probabilities API
    2. LM Parameters API
    3. Omni-Model API
4. Personas API
    1. Persona metadata
       1. System text
       2. Name
       3. Description
       4. Type
       5. Owner
5. Embeddings API
    1. Embedding model
    2. Embedding metadata 
        1. Vector size
        2. Embedding type
6. RAG API
    1. Index metadata
        1. Embedding model 
        2. Index labels
        3. Index Owner
        4. Index Sharing
        5. Index Security
        6. Document metadata 
        7. Images
        8. OCR
        9. Tables
        10. Code 
    2. Chunk metadata
    3. Vector DB API
    4. Citations
7. Prompt Memory API
8. Prompt Classification API
9. Guardrails API
    1. Prompt Guardrails
    2. Completion Guardrails 
10. Custom Functions API
11. Web Search API
12. Assistants API
    1. Code Interpretation/Execution
13. Code Generation API
14. Validation API
15. Agent Framework API

## Installation

To build this software you will first have to insure you have the following pre-requisites:
1. Latest Version of Java. You can download it here.
2. gRPC. You can download it here.
3. Maven.  The project has a pom file.  The two key POM lifecycle commands are Compile and Package.
  The package command creates a runnable Jar file that you can use to run both the client and the server. 
5. Accounts and API keys with all the major LLM/SLM providers.
6. Set up environment variables for all the API Keys.
    1. OPENAI_API_KEY
    2. GEMINI_PROJECT_ID

## Usage

To run the gRPC server you type:
```bash
java -jar ./target/xlm-eco-api-1.0-SNAPSHOT.jar
```

To run the java test gRPC client you type:
```bash
java -cp ./target/xlm-eco-api-1.0-SNAPSHOT.jar us.daconta.xlmeco.GrpcXlmClient gemini "What is the Capital of France?"
```

Note: there will be a client created for every language supported by gRPC (Python, C#, C, Go, Rust, etc.)

## Conclusion 

Feedback on the project is welcome.  You can email me at michael dot daconta at gmail dot com.
