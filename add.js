// Funk Runtime Library
const FunkRuntime = {
    variant: (constructor, data = null) => ({ __type: 'variant', constructor, data }),
    record: (fields) => ({ __type: 'record', ...fields }),
    updateRecord: (record, updates) => ({ ...record, ...updates })
};

const add = ({ x, y }) => (x + y);
