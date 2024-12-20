#!/bin/bash

if [ $# -lt 5 ]; then
    echo "Usage: $0 <host> <grpc_port> <flask_port> <provider> <model_name>"
    exit 1
fi

HOST=$1
GRPC_PORT=$2
FLASK_PORT=$3
PROVIDER=$4
MODEL_NAME=$5

# Get the absolute path to the script's directory
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Set PYTHONPATH to include the generated directory
export PYTHONPATH="$SCRIPT_DIR/../../python_client/generated"

# Run the client script and pass all received arguments
python "$SCRIPT_DIR/app.py" "$HOST" "$GRPC_PORT" "$FLASK_PORT" "$PROVIDER" "$MODEL_NAME"




