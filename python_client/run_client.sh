#!/bin/bash

# Get the absolute path to the script's directory
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Set PYTHONPATH to include the generated directory
export PYTHONPATH="$SCRIPT_DIR/generated"

# Run the client script and pass all received arguments
python "$SCRIPT_DIR/xlm_client.py" "$@"




